package com.amapilot.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.amapilot.R

/** 高德隐私合规弹窗：同意前不初始化任何地图/定位能力，不同意则退出应用。 */
@Composable
fun PrivacyPolicyDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDisagree,
        title = { Text(stringResource(R.string.privacy_title)) },
        text = { Text(stringResource(R.string.privacy_body)) },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text(stringResource(R.string.privacy_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text(stringResource(R.string.privacy_disagree))
            }
        }
    )
}
