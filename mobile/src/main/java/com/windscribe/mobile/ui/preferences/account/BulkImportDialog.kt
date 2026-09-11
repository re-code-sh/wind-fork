/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.mobile.ui.preferences.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.windscribe.mobile.ui.theme.AppColors
import com.windscribe.mobile.ui.theme.font12
import com.windscribe.mobile.ui.theme.font14
import com.windscribe.mobile.ui.theme.font16
import com.windscribe.mobile.ui.theme.preferencesBackgroundColor
import com.windscribe.mobile.ui.theme.preferencesSubtitleColor
import com.windscribe.mobile.ui.theme.primaryTextColor
import com.windscribe.vpn.R

@Composable
fun BulkImportDialog(
    state: BulkImportProgressState?,
    onStartImport: (String) -> Unit,
    onCancelImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val isRunning = state?.isRunning == true
    val isCompleted = state?.isCompleted == true

    Dialog(onDismissRequest = {
        if (!isRunning) onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.preferencesBackgroundColor,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
            ) {
                // Header
                Text(
                    text =
                        if (isCompleted) {
                            stringResource(R.string.bulk_import_completed)
                        } else {
                            stringResource(R.string.bulk_import_accounts)
                        },
                    style =
                        font16.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primaryTextColor,
                        ),
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (!isRunning && !isCompleted) {
                    // Input Mode
                    Text(
                        text = stringResource(R.string.bulk_import_description),
                        style =
                            font12.copy(
                                color = MaterialTheme.colorScheme.preferencesSubtitleColor,
                            ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Anti-abuse safety callout
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    color = AppColors.actionGreen.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                ).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = AppColors.actionGreen,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.bulk_import_anti_abuse_notice),
                            style =
                                font12.copy(
                                    color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.9f),
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.bulk_import_placeholder),
                                style = font12.copy(color = MaterialTheme.colorScheme.preferencesSubtitleColor),
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.primaryTextColor,
                                unfocusedTextColor = MaterialTheme.colorScheme.primaryTextColor,
                                focusedBorderColor = AppColors.actionGreen,
                                unfocusedBorderColor = MaterialTheme.colorScheme.preferencesSubtitleColor.copy(alpha = 0.4f),
                            ),
                        textStyle = font14,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.preferencesSubtitleColor,
                                ),
                        ) {
                            Text(text = stringResource(R.string.cancel), style = font14)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onStartImport(inputText) },
                            enabled = inputText.isNotBlank(),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = AppColors.actionGreen,
                                    contentColor = Color.White,
                                ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.bulk_import_start),
                                style = font14.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                } else if (isRunning) {
                    // Running Mode
                    val current = state?.current ?: 0
                    val total = state?.total ?: 0
                    val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f

                    Text(
                        text = stringResource(R.string.bulk_import_in_progress, current, total),
                        style =
                            font14.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primaryTextColor,
                            ),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        color = AppColors.actionGreen,
                        trackColor = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.1f),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = state?.statusMessage ?: "",
                        style =
                            font12.copy(
                                color = MaterialTheme.colorScheme.preferencesSubtitleColor,
                            ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Success: ${state?.successCount ?: 0} | Failed: ${state?.failedCount ?: 0}",
                            style = font12.copy(color = MaterialTheme.colorScheme.preferencesSubtitleColor),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = onCancelImport,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = AppColors.red.copy(alpha = 0.8f),
                                    contentColor = Color.White,
                                ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(text = stringResource(R.string.cancel), style = font14)
                        }
                    }
                } else {
                    // Completed Mode
                    val success = state?.successCount ?: 0
                    val failed = state?.failedCount ?: 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    color =
                                        if (failed == 0) {
                                            AppColors.actionGreen.copy(alpha = 0.15f)
                                        } else {
                                            AppColors.red.copy(alpha = 0.15f)
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                ).padding(12.dp),
                    ) {
                        Icon(
                            imageVector =
                                if (failed == 0) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.ErrorOutline
                                },
                            contentDescription = null,
                            tint = if (failed == 0) AppColors.actionGreen else AppColors.red,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.bulk_import_summary, success, failed),
                            style =
                                font14.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primaryTextColor,
                                ),
                        )
                    }

                    if (!state?.failures.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Failed Accounts:",
                            style =
                                font12.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primaryTextColor,
                                ),
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                                    .verticalScroll(rememberScrollState()),
                        ) {
                            state?.failures?.forEach { (user, reason) ->
                                Text(
                                    text = "• $user: $reason",
                                    style =
                                        font12.copy(
                                            color = AppColors.red,
                                        ),
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = AppColors.actionGreen,
                                    contentColor = Color.White,
                                ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.ok),
                                style = font14.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }
        }
    }
}
