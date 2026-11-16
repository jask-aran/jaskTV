/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/** Logging for shared code: android.util.Log on Android, standard error on desktop. */
expect object Log {
    fun d(tag: String, message: String): Int
    fun i(tag: String, message: String): Int
    fun w(tag: String, message: String, error: Throwable? = null): Int
    fun e(tag: String, message: String, error: Throwable? = null): Int
}
