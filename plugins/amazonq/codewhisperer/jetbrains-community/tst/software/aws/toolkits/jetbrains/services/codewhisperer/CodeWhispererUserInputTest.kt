// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import software.aws.toolkits.jetbrains.services.codewhisperer.CodeWhispererTestUtil.pythonResponse

class CodeWhispererUserInputTest : CodeWhispererTestBase() {

    @Test
    fun `test no user input should show all recommendations`() {
        addUserInputAfterInvocation("")

        withCodeWhispererServiceInvokedAndWait { states ->
            val actualRecommendations = states.recommendationContext.details.map {
                it.recommendation.content()
            }
            assertThat(actualRecommendations).isEqualTo(pythonResponse.completions().map { it.content() })
        }
    }

    @Test
    fun `test have user input should show that recommendations prefix-matching user input are valid`() {
        val userInput = "test"
        addUserInputAfterInvocation(userInput)

        val expectedRecommendations = pythonResponse.completions().map { it.content() }

        withCodeWhispererServiceInvokedAndWait { states ->
            val actualRecommendations = states.recommendationContext.details.map { it.recommendation.content() }
            assertThat(actualRecommendations).isEqualTo(expectedRecommendations)
            states.recommendationContext.details.forEachIndexed { index, context ->
                val expectedDiscarded = !pythonResponse.completions()[index].content().startsWith(userInput)
                val actualDiscarded = context.isDiscarded
                assertThat(actualDiscarded).isEqualTo(expectedDiscarded)
            }
        }
    }

    @Test
    fun `test have user input and typeahead should show that recommendations prefix-matching user input + typeahead are valid`() {
        val userInput = "test"
        addUserInputAfterInvocation(userInput)

        val typeahead = " recommendation"

        withCodeWhispererServiceInvokedAndWait { states ->
            projectRule.fixture.type(typeahead)
            assertThat(popupManagerSpy.sessionContext.typeahead).isEqualTo(typeahead)
            states.recommendationContext.details.forEachIndexed { index, actualContext ->
                val actualDiscarded = actualContext.isDiscarded
                val expectedDiscarded = !pythonResponse.completions()[index].content().startsWith(userInput + typeahead)
                assertThat(actualDiscarded).isEqualTo(expectedDiscarded)
            }
        }
    }

    @Test
    fun `test have blank user input should show that all recommendations are valid`() {
        val blankUserInput = "    "
        addUserInputAfterInvocation(blankUserInput)
        val userInput = blankUserInput.trimStart()

        withCodeWhispererServiceInvokedAndWait { states ->
            assertThat(states.recommendationContext.userInputSinceInvocation).isEqualTo(userInput)
            states.recommendationContext.details.forEachIndexed { _, actualContext ->
                assertThat(actualContext.isDiscarded).isEqualTo(false)
            }
        }
    }

    @Test
    fun `test have user input with leading spaces and matching suffix should show recommendations prefix-matching suffix are valid`() {
        val userInputWithLeadingSpaces = "   test"
        addUserInputAfterInvocation(userInputWithLeadingSpaces)
        val userInput = userInputWithLeadingSpaces.trimStart()

        withCodeWhispererServiceInvokedAndWait { states ->
            assertThat(states.recommendationContext.userInputSinceInvocation).isEqualTo(userInput)
            states.recommendationContext.details.forEachIndexed { index, actualContext ->
                val actualDiscarded = actualContext.isDiscarded
                val expectedDiscarded = !pythonResponse.completions()[index].content().startsWith(userInput)
                assertThat(actualDiscarded).isEqualTo(expectedDiscarded)
            }
        }
    }
}
