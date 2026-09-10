/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.mobile.ui.preferences.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.windscribe.mobile.ui.helper.hapticClickable
import com.windscribe.mobile.ui.theme.AppColors
import com.windscribe.mobile.ui.theme.font12
import com.windscribe.mobile.ui.theme.font14
import com.windscribe.mobile.ui.theme.font16
import com.windscribe.mobile.ui.theme.preferencesBackgroundColor
import com.windscribe.mobile.ui.theme.preferencesSubtitleColor
import com.windscribe.mobile.ui.theme.primaryTextColor
import com.windscribe.vpn.R
import com.windscribe.vpn.commonutils.Ext.toLabel
import com.windscribe.vpn.localdatabase.tables.AccountEntity

@Composable
fun AccountVaultSection(
    accounts: List<AccountEntity>,
    activeAccount: AccountEntity?,
    onSwitch: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var accountPendingRemoval by remember { mutableStateOf<AccountEntity?>(null) }
    val inactiveAccounts = accounts.filter { it.id != activeAccount?.id }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.account_vault),
            style =
                font12.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.preferencesSubtitleColor,
                ),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.vault_description),
            style =
                font12.copy(
                    color = MaterialTheme.colorScheme.preferencesSubtitleColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Start,
                ),
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Active Account Card
        if (activeAccount != null) {
            ActiveAccountCard(account = activeAccount)
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Inactive Accounts
        if (inactiveAccounts.isNotEmpty()) {
            inactiveAccounts.forEach { account ->
                InactiveAccountItem(
                    account = account,
                    onSwitch = { onSwitch(account.id) },
                    onRemoveRequest = { accountPendingRemoval = account },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Add Another Account Button
        AddAccountButton(onClick = onAddAccount)
    }

    // Removal confirmation dialog
    accountPendingRemoval?.let { target ->
        RemoveAccountDialog(
            account = target,
            onConfirm = {
                onRemove(target.id)
                accountPendingRemoval = null
            },
            onDismiss = { accountPendingRemoval = null },
        )
    }
}

@Composable
private fun ActiveAccountCard(account: AccountEntity) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp),
                ).border(
                    width = 1.dp,
                    color = AppColors.actionGreen.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ).padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = account.username,
                style =
                    font16.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primaryTextColor,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .background(
                            color = AppColors.actionGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                        ).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.active_badge),
                    style =
                        font12.copy(
                            color = AppColors.actionGreen,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (account.isPro) stringResource(R.string.pro) else stringResource(R.string.free),
                style =
                    font14.copy(
                        color = if (account.isPro) AppColors.cyberBlue else MaterialTheme.colorScheme.primaryTextColor,
                        fontWeight = FontWeight.Medium,
                    ),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (account.isPro) stringResource(R.string.unlimited_data) else account.dataLeft.toLabel(),
                style =
                    font14.copy(
                        color = MaterialTheme.colorScheme.preferencesSubtitleColor,
                        fontWeight = FontWeight.Normal,
                    ),
            )
        }
    }
}

@Composable
private fun InactiveAccountItem(
    account: AccountEntity,
    onSwitch: () -> Unit,
    onRemoveRequest: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp),
                ).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.username,
                style =
                    font16.copy(
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primaryTextColor,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (account.isPro) stringResource(R.string.pro) else stringResource(R.string.free),
                    style =
                        font12.copy(
                            color = if (account.isPro) AppColors.cyberBlue else MaterialTheme.colorScheme.primaryTextColor,
                            fontWeight = FontWeight.Medium,
                        ),
                )
                Text(
                    text = " • ",
                    style = font12.copy(color = MaterialTheme.colorScheme.preferencesSubtitleColor),
                )
                Text(
                    text = if (account.isPro) stringResource(R.string.unlimited_data) else account.dataLeft.toLabel(),
                    style = font12.copy(color = MaterialTheme.colorScheme.preferencesSubtitleColor),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .background(
                            color = AppColors.cyberBlue.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                        ).hapticClickable { onSwitch() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = stringResource(R.string.switch_account),
                        tint = AppColors.cyberBlue,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.switch_account),
                        style =
                            font14.copy(
                                color = AppColors.cyberBlue,
                                fontWeight = FontWeight.SemiBold,
                            ),
                    )
                }
            }
            IconButton(
                onClick = onRemoveRequest,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.remove_account),
                    tint = AppColors.red.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AddAccountButton(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp),
                ).border(
                    width = 1.dp,
                    color = AppColors.actionGreen.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ).hapticClickable { onClick() }
                .padding(vertical = 12.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = AppColors.actionGreen,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.add_another_account),
            style =
                font16.copy(
                    fontWeight = FontWeight.Medium,
                    color = AppColors.actionGreen,
                ),
        )
    }
}

@Composable
private fun RemoveAccountDialog(
    account: AccountEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryTextColor,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.remove_account_title),
                    style =
                        font16.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.preferencesBackgroundColor,
                        ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.remove_account_confirmation, account.username),
                    style =
                        font14.copy(
                            color = MaterialTheme.colorScheme.preferencesBackgroundColor.copy(alpha = 0.8f),
                            textAlign = TextAlign.Start,
                        ),
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.preferencesBackgroundColor,
                            ),
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = font14,
                            color = MaterialTheme.colorScheme.preferencesBackgroundColor.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = AppColors.red,
                                contentColor = Color.White,
                            ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.remove_account),
                            style = font14.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
