package com.mucacran.infocontainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mucacran.infocontainer.R

@Composable
internal fun PhotoAnalysisSection(formState: InspectionFormState) {
    val readiness = formState.photoAnalysisReadiness
    val message = when (readiness) {
        PhotoAnalysisReadiness.BUSY -> R.string.photo_analysis_busy
        PhotoAnalysisReadiness.RECOVERY_REQUIRED -> R.string.photo_analysis_recovery
        PhotoAnalysisReadiness.PHOTOS_REQUIRED -> R.string.photo_analysis_photos_required
        PhotoAnalysisReadiness.REASON_REQUIRED -> R.string.photo_analysis_reason_required
        PhotoAnalysisReadiness.SERVER_UNAVAILABLE -> R.string.photo_analysis_unavailable
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("form_analysis")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.photo_analysis_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(message),
                Modifier.testTag("form_analysis_status").semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (readiness != PhotoAnalysisReadiness.SERVER_UNAVAILABLE) {
                Text(stringResource(R.string.photo_analysis_unavailable), style = MaterialTheme.typography.bodySmall)
            }
            // No compatible end-to-end REST flow or budget authorization exists.
            // Keep this action disabled; never substitute a demo or trigger work on photo changes.
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("form_analyze")) {
                Text(stringResource(R.string.btn_send_analyze_photos))
            }
            Text(stringResource(R.string.photo_analysis_scope), style = MaterialTheme.typography.bodySmall)
        }
    }
}
