/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * The small key-value store settings live in: SharedPreferences on Android, a properties file
 * on desktop. Same shape as SharedPreferences so settings code reads the same on both.
 */
interface Prefs {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun remove(key: String): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** Opens (or creates) the named store for this platform. */
expect fun openPrefs(name: String): Prefs
