// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.explorer.webview

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefClient
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import migration.software.aws.toolkits.jetbrains.core.credentials.CredentialManager
import org.cef.CefApp
import software.aws.toolkits.core.credentials.validatedSsoIdentifierFromUrl
import software.aws.toolkits.core.region.AwsRegion
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.warn
import software.aws.toolkits.jetbrains.core.credentials.AwsBearerTokenConnection
import software.aws.toolkits.jetbrains.core.credentials.Login
import software.aws.toolkits.jetbrains.core.credentials.ToolkitAuthManager
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnectionManager
import software.aws.toolkits.jetbrains.core.credentials.actions.SsoLogoutAction
import software.aws.toolkits.jetbrains.core.credentials.lazyIsUnauthedBearerConnection
import software.aws.toolkits.jetbrains.core.credentials.pinning.CodeCatalystConnection
import software.aws.toolkits.jetbrains.core.credentials.sono.CODECATALYST_SCOPES
import software.aws.toolkits.jetbrains.core.credentials.sono.IDENTITY_CENTER_ROLE_ACCESS_SCOPE
import software.aws.toolkits.jetbrains.core.credentials.sono.isSono
import software.aws.toolkits.jetbrains.core.explorer.ShowToolkitListener
import software.aws.toolkits.jetbrains.core.gettingstarted.IdcRolePopup
import software.aws.toolkits.jetbrains.core.gettingstarted.IdcRolePopupState
import software.aws.toolkits.jetbrains.core.region.AwsRegionProvider
import software.aws.toolkits.jetbrains.core.webview.BrowserMessage
import software.aws.toolkits.jetbrains.core.webview.BrowserState
import software.aws.toolkits.jetbrains.core.webview.LoginBrowser
import software.aws.toolkits.jetbrains.core.webview.WebviewResourceHandlerFactory
import software.aws.toolkits.jetbrains.isDeveloperMode
import software.aws.toolkits.jetbrains.utils.isQWebviewsAvailable
import software.aws.toolkits.jetbrains.utils.isTookitConnected
import software.aws.toolkits.telemetry.FeatureId
import software.aws.toolkits.telemetry.UiTelemetry
import java.awt.event.ActionListener
import java.net.URI
import javax.swing.JButton
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class ToolkitWebviewPanel(val project: Project, private val scope: CoroutineScope) : Disposable {
    private val webviewContainer = Wrapper()
    var browser: ToolkitWebviewBrowser? = null
        private set

    val component = panel {
        row {
            cell(webviewContainer)
                .align(Align.FILL)
        }.resizableRow()

        if (isDeveloperMode()) {
            row {
                cell(
                    JButton("Show Web Debugger").apply {
                        addActionListener(
                            ActionListener {
                                browser?.jcefBrowser?.openDevtools()
                            },
                        )
                    },
                )
                    .align(Align.FILL)
            }
        }
    }

    // TODO: A simplified version of theme flow that only listen for LAF dark mode changes, will refactor later
    private val lafFlow = callbackFlow {
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                try {
                    trySend(!JBColor.isBright())
                } catch (e: Exception) {
                    LOG.error(e) { "Cannot send dark mode status" }
                }
            }
        )

        send(!JBColor.isBright())
        awaitClose { connection.disconnect() }
    }

    init {
        if (!isQWebviewsAvailable()) {
            // Fallback to an alternative browser-less solution
            webviewContainer.add(JBTextArea("JCEF not supported"))
            browser = null
        } else {
            browser = ToolkitWebviewBrowser(project, this).also {
                webviewContainer.add(it.component())
            }
        }

        lafFlow
            .distinctUntilChanged()
            .onEach {
                val cefBrowser = browser?.jcefBrowser?.cefBrowser ?: return@onEach
                cefBrowser.executeJavaScript("window.changeTheme($it)", cefBrowser.url, 0)
            }
            .launchIn(scope)
    }

    companion object {
        fun getInstance(project: Project?) = project?.service<ToolkitWebviewPanel>() ?: error("")
        private val LOG = getLogger<ToolkitWebviewPanel>()
    }

    override fun dispose() {}
}

// TODO: STILL WIP thus duplicate code / pending move to plugins/toolkit
class ToolkitWebviewBrowser(val project: Project, private val parentDisposable: Disposable) : LoginBrowser(
    project,
    ToolkitWebviewBrowser.DOMAIN,
    ToolkitWebviewBrowser.WEB_SCRIPT_URI
) {
    // TODO: confirm if we need such configuration or the default is fine
    // TODO: move JcefBrowserUtils to core
    override val jcefBrowser: JBCefBrowserBase by lazy {
        val client = JBCefApp.getInstance().createClient().apply {
            setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 5)
        }
        Disposer.register(parentDisposable, client)
        JBCefBrowserBuilder()
            .setClient(client)
            .setOffScreenRendering(true)
            .build()
    }
    private val query: JBCefJSQuery = JBCefJSQuery.create(jcefBrowser)

    init {
        CefApp.getInstance()
            .registerSchemeHandlerFactory(
                "http",
                domain,
                WebviewResourceHandlerFactory(
                    domain = "http://$domain/",
                    assetUri = "/webview/assets/"
                ),
            )

        loadWebView(query)

        query.addHandler(jcefHandler)
    }

    override fun handleBrowserMessage(message: BrowserMessage?) {
        if (message == null) {
            return
        }

        when (message) {
            is BrowserMessage.PrepareUi -> {
                val cancellable = isTookitConnected(project)
                this.prepareBrowser(BrowserState(FeatureId.AwsExplorer, browserCancellable = cancellable))
            }

            is BrowserMessage.SelectConnection -> {
                this.selectionSettings[message.connectionId]?.let { settings ->
                    settings.onChange(settings.currentSelection)
                }
            }

            is BrowserMessage.LoginBuilderId -> {
                loginBuilderId(CODECATALYST_SCOPES)
            }

            is BrowserMessage.LoginIdC -> {
                val awsRegion = AwsRegionProvider.getInstance()[message.region] ?: error("unknown region returned from Toolkit browser")

                val scopes = if (FeatureId.from(message.feature) == FeatureId.Codecatalyst) {
                    CODECATALYST_SCOPES
                } else {
                    listOf(IDENTITY_CENTER_ROLE_ACCESS_SCOPE)
                }

                loginIdC(message.url, awsRegion, scopes)
            }

            is BrowserMessage.LoginIAM -> {
                loginIAM(message.profileName, message.accessKey, message.secretKey)
            }

            is BrowserMessage.ToggleBrowser -> {
                ShowToolkitListener.showExplorerTree(project)
            }

            is BrowserMessage.CancelLogin -> {
                cancelLogin()
            }

            is BrowserMessage.Signout -> {
                ToolkitConnectionManager.getInstance(project).activeConnectionForFeature(CodeCatalystConnection.getInstance())?.let { connection ->
                    connection as AwsBearerTokenConnection
                    SsoLogoutAction(connection).actionPerformed(
                        AnActionEvent.createFromDataContext(
                            "toolkitBrowser",
                            null,
                            DataContext.EMPTY_CONTEXT
                        )
                    )
                }
            }

            is BrowserMessage.Reauth -> {
                reauth(ToolkitConnectionManager.getInstance(project).activeConnectionForFeature(CodeCatalystConnection.getInstance()))
            }

            is BrowserMessage.SendUiClickTelemetry -> {
                val signInOption = message.signInOptionClicked
                if (signInOption.isNullOrEmpty()) {
                    LOG.warn { "Unknown sign in option" }
                } else {
                    UiTelemetry.click(project, signInOption)
                }
            }

            is BrowserMessage.SwitchProfile -> {}

            is BrowserMessage.ListProfiles -> {}

            is BrowserMessage.PublishWebviewTelemetry -> {
                publishTelemetry(message)
            }

            is BrowserMessage.OpenUrl -> {
                BrowserUtil.browse(URI(message.externalLink))
            }
        }
    }

    override fun prepareBrowser(state: BrowserState) {
        selectionSettings.clear()

        if (!isTookitConnected(project)) {
            // existing connections
            val bearerCreds = ToolkitAuthManager.getInstance().listConnections()
                .filterIsInstance<AwsBearerTokenConnection>()
                .associate {
                    it.id to BearerConnectionSelectionSettings(it) { conn ->
                        if (conn.isSono()) {
                            loginBuilderId(CODECATALYST_SCOPES)
                        } else {
                            // TODO: rewrite scope logic, it's short term solution only
                            AwsRegionProvider.getInstance()[conn.region]?.let { region ->
                                loginIdC(conn.startUrl, region, listOf(IDENTITY_CENTER_ROLE_ACCESS_SCOPE))
                            }
                        }
                    }
                }

            selectionSettings.putAll(bearerCreds)
        }

        // previous login
        val lastLoginIdcInfo = ToolkitAuthManager.getInstance().getLastLoginIdcInfo().apply {
            // set default option as us-east-1
            if (this.region.isBlank()) {
                this.region = AwsRegionProvider.getInstance().defaultRegion().id
            }
        }

        // available regions
        val regions = AwsRegionProvider.getInstance().allRegionsForService("sso").values
        val regionJson = writeValueAsString(regions)

        // TODO: if codecatalyst connection expires, set stage to 'REAUTH'
        // TODO: make these strings type safe
        val stage = if (state.feature == FeatureId.Codecatalyst) {
            "SSO_FORM"
        } else if (shouldPromptToolkitReauth(project)) {
            "REAUTH"
        } else {
            "START"
        }

        val jsonData = """
            {
                stage: '$stage',
                regions: $regionJson,
                idcInfo: {
                    profileName: '${lastLoginIdcInfo.profileName}',
                    startUrl: '${lastLoginIdcInfo.startUrl}',
                    region: '${lastLoginIdcInfo.region}'
                },
                cancellable: ${state.browserCancellable},
                feature: '${state.feature}',
                existConnections: ${writeValueAsString(selectionSettings.values.map { it.currentSelection }.toList())}
            }
        """.trimIndent()
        executeJS("window.ideClient.prepareUi($jsonData)")
    }

    override fun loginIdC(url: String, region: AwsRegion, scopes: List<String>) {
        val (onIdCError: (Exception) -> Unit, onIdCSuccess: () -> Unit) = getSuccessAndErrorActionsForIdcLogin(scopes, url, region)

        val login = Login.IdC(url, region, scopes, onPendingToken, onIdCSuccess, onIdCError)

        loginWithBackgroundContext {
            val connection = login.login(project)

            if (connection != null && scopes.contains(IDENTITY_CENTER_ROLE_ACCESS_SCOPE)) {
                val tokenProvider = connection.getConnectionSettings().tokenProvider

                runInEdt {
                    val rolePopup = IdcRolePopup(
                        project,
                        region.id,
                        validatedSsoIdentifierFromUrl(url),
                        tokenProvider,
                        IdcRolePopupState(), // TODO: is it correct <<?
                    )
                    rolePopup.show()
                }
            }
        }
    }

    override fun loadWebView(query: JBCefJSQuery) {
        jcefBrowser.loadHTML(getWebviewHTML(webScriptUri, query))
    }

    fun component(): JComponent? = jcefBrowser.component

    companion object {
        private val LOG = getLogger<ToolkitWebviewBrowser>()
        private const val WEB_SCRIPT_URI = "http://webview/js/toolkitGetStart.js"
        private const val DOMAIN = "webview"
    }
}

fun shouldPromptToolkitReauth(project: Project) = ToolkitConnectionManager.getInstance(project).let {
    val codecatalystRequiresReauth = it.activeConnectionForFeature(CodeCatalystConnection.getInstance())?.let { codecatalyst ->
        if (codecatalyst is AwsBearerTokenConnection) {
            codecatalyst.lazyIsUnauthedBearerConnection()
        } else {
            // should not be this case as codecatalyst is always AwsBearerTokenConnection
            false
        }
        // if no codecatalyst connection, we need signin instead of reauth
    } ?: false

    // only prompt reauth if no other credential
    CredentialManager.getInstance().getCredentialIdentifiers().isEmpty() && codecatalystRequiresReauth
}
