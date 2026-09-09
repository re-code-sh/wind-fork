/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.localdatabase.tables

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "account_vault",
    indices = [Index(value = ["username"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val sessionAuthHash: String,
    val rawSessionJson: String,
    val dataLeft: Long,
    val trafficMax: Long,
    val trafficUsed: Long,
    val isPro: Boolean,
    val isActive: Boolean,
    val virtualCuid: String,
    val virtualMac: String,
    val virtualHostName: String,
    val sessionStatus: String = "VALID",
    val lastSyncTimestamp: Long = System.currentTimeMillis(),
)
