package tech.currenta.tldr

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ResultScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun mount(report: JSONObject? = null, noClaims: Boolean = false, onQuestion: suspend (String, JSONArray, String?) -> String = { _, _, _ -> "An answer." }) {
        val selected = mutableStateOf("Summary")
        val job = JSONObject("""{"id":"job","status":"completed","url":"https://youtu.be/test","summary":{"title":"Test video","short_summary":"The quick summary.","summary":"Legacy long text.","key_points":["Legacy key point"]}}""")
        if (report != null) job.put("fact_check", report)
        if (noClaims) job.getJSONObject("summary").put("important_claims", JSONArray())
        compose.setContent { UtilityTheme("Dark") {
            CompletedResult(job, selected.value, { selected.value = it }, onQuestion,
                onCheck = { JSONObject("""{"note":"No checkable claims.","claims":[]}""") },
                onError = { throw AssertionError(it) }, snackbar = SnackbarHostState())
        } }
    }

    @Test fun onlyTwoTabsAndQuickSummaryAreShownWithAccessibleIconActions() {
        mount()
        compose.onNodeWithText("Summary").assertIsDisplayed()
        compose.onNodeWithText("Check").assertIsDisplayed()
        compose.onNodeWithText("Ask").assertDoesNotExist()
        compose.onNodeWithText("Transcript").assertDoesNotExist()
        compose.onNodeWithText("Standard").assertDoesNotExist()
        compose.onNodeWithText("Legacy long text.").assertDoesNotExist()
        compose.onNodeWithText("The quick summary.").assertIsDisplayed()
        listOf("Copy summary", "Share summary", "Open original video").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithText("Check").performClick()
        compose.onNodeWithText("Ask about this video").assertIsDisplayed()
    }

    @Test fun followUpQuestionReceivesOnlyThisConversationsCompletedExchanges() {
        val requests = mutableListOf<Pair<String, JSONArray>>()
        mount { question, history, _ -> requests.add(question to history); "Answer ${requests.size}." }
        compose.onNode(hasSetTextAction()).performTextInput("What is the main point?")
        compose.onNodeWithContentDescription("Send question").performClick()
        compose.waitForIdle()
        assertEquals(0, requests[0].second.length())
        compose.onNode(hasSetTextAction()).performTextInput("Why is that?")
        compose.onNodeWithContentDescription("Send question").performClick()
        compose.waitForIdle()
        assertEquals(2, requests.size)
        assertEquals("What is the main point?", requests[1].second.getJSONObject(0).getString("content"))
        assertEquals("Answer 1.", requests[1].second.getJSONObject(1).getString("content"))
        compose.onNodeWithText("Answer 2.").assertExists()
        compose.onNodeWithText("Ask about this video").assertIsDisplayed()
    }

    @Test fun failedQuestionCanBeRetriedWithoutDuplicatingTheQuestion() {
        var calls = 0
        mount { _, _, _ -> if (++calls == 1) throw Exception("Connection lost") else "Recovered answer." }
        compose.onNode(hasSetTextAction()).performTextInput("Explain this.")
        compose.onNodeWithContentDescription("Send question").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Retry answer").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, calls)
        compose.onAllNodesWithText("Explain this.").assertCountEquals(1)
        compose.onNodeWithText("Recovered answer.").assertExists()
    }

    private fun claimReport() = JSONObject("""{"note":"Consult original sources for full context.","claims":[{"claim":"A specific claim that needs context.","verdict":"Misleading / missing context","explanation":"This claim leaves out an important qualification.","sources":[{"title":"Research paper","url":"https://example.org/research","excerpt":"<strong>Evidence</strong> text"}]}]}""")

    @Test fun serverOverviewKeepsFullExplanationsBehindDetails() {
        mount(report = claimReport().put("overview", "The claim omits important context."))
        compose.onNodeWithText("Check").performClick()
        compose.onNodeWithText("The claim omits important context.").assertIsDisplayed()
        compose.onNodeWithText("This claim leaves out an important qualification.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("View details").performScrollTo().performClick()
        compose.onNodeWithText("A specific claim that needs context.").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("This claim leaves out an important qualification.").assertIsDisplayed()
    }

    @Test fun videosWithoutFactualClaimsStillHaveASummaryAndQuestions() {
        mount(noClaims = true)
        compose.onNodeWithText("The quick summary.").assertIsDisplayed()
        compose.onNodeWithText("Check").performClick()
        compose.onNodeWithText("No factual claims to check were identified in this video.").assertIsDisplayed()
        compose.onNodeWithText("Check claims").assertDoesNotExist()
        compose.onNodeWithText("Ask about this video").assertIsDisplayed()
    }

    @Test fun checkOverviewOpensDetailsWithProgressiveSourceExcerpts() {
        mount(report = claimReport())
        compose.onNodeWithText("Check").performClick()
        compose.onNodeWithText("1 claim checked").assertExists()
        compose.onNodeWithText("Missing context").assertDoesNotExist()
        compose.onNodeWithText("Compare claims with external sources.").assertDoesNotExist()
        compose.onNodeWithText("The claim “A specific claim that needs context.” is misleading or missing context. This claim leaves out an important qualification.").assertIsDisplayed()
        compose.onNodeWithText("Research paper").assertDoesNotExist()
        compose.onNodeWithText("View details").performScrollTo().performClick()
        compose.onNodeWithText("Missing context").assertExists()
        compose.onNodeWithText("A specific claim that needs context.").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Claim details").assertIsDisplayed()
        compose.onNodeWithText("This claim leaves out an important qualification.").assertIsDisplayed()
        compose.onNodeWithText("Evidence text").assertDoesNotExist()
        compose.onNodeWithText("Show excerpt").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Evidence text").assertExists()
        compose.onNodeWithContentDescription("Close claim details").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Claim details").assertDoesNotExist()
        compose.onNodeWithText("Ask about this video").assertIsDisplayed()
    }

    @Test fun claimQuestionCarriesItsContextAndCanReturnToGeneralVideoQuestions() {
        var selectedClaim: String? = null
        mount(report = claimReport()) { _, _, claim -> selectedClaim = claim; "A contextual answer." }
        compose.onNodeWithText("Check").performClick()
        compose.onNodeWithText("View details").performScrollTo().performClick()
        compose.onNodeWithText("A specific claim that needs context.").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Ask about this claim").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Claim details").assertDoesNotExist()
        compose.onNodeWithText("About this claim").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("What is missing?")
        compose.onNodeWithContentDescription("Send question").performClick()
        compose.waitForIdle()
        assertEquals("A specific claim that needs context.", selectedClaim)
        compose.onNodeWithContentDescription("Clear claim context").performClick()
        compose.onNodeWithText("Ask about this video").assertIsDisplayed()
    }

    @Test fun methodologyIsAvailableWithoutOccupyingTheClaimList() {
        mount(report = claimReport())
        compose.onNodeWithText("Check").performClick()
        compose.onNodeWithText("Consult original sources for full context.").assertDoesNotExist()
        compose.onNodeWithText("About this check").performScrollTo().performClick()
        compose.onNodeWithText("Consult original sources for full context.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close check information").performClick()
        compose.onNodeWithText("1 claim checked").assertExists()
    }
}
