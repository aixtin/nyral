#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# patch_db.py — MemoryDb.kt 会话隔离：sessions 加 mode 列(0=agent,1=chat)，DB v8->v9
import io, sys

path = "/home/ymz/Nyral/android-agent-app/app/src/main/java/all/MemoryDb.kt"
d = io.open(path, encoding="utf-8").read()

def rep(old, new, tag):
    global d
    c = d.count(old)
    if c != 1:
        print(f"[FAIL] {tag}: count={c}")
        sys.exit(1)
    d = d.replace(old, new, 1)
    print(f"[OK] {tag}")

# 1. DB 版本 8 -> 9
rep('SQLiteOpenHelper(context, "memory.db", null, 8)',
    'SQLiteOpenHelper(context, "memory.db", null, 9)',
    "version 8->9")

# 2. onCreate sessions 表加 mode 列
rep('''            "CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "pinned INTEGER NOT NULL DEFAULT 0)"''',
    '''            "CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "pinned INTEGER NOT NULL DEFAULT 0," +
                "mode INTEGER NOT NULL DEFAULT 0)"''',
    "onCreate sessions.mode")

# 3. onUpgrade oldVersion<9: sessions 补 mode 列（幂等）
rep('''    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {''',
    '''    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 9) {
            // sessions 新增 mode 列(v9)：0=Agent 模式, 1=聊天模式，会话隔离
            val sessMode = db.rawQuery("PRAGMA table_info(sessions)", null)
            var hasMode = false
            while (sessMode.moveToNext()) {
                if (sessMode.getString(1) == "mode") {
                    hasMode = true
                    break
                }
            }
            sessMode.close()
            if (!hasMode) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN mode INTEGER NOT NULL DEFAULT 0")
            }
        }
        if (oldVersion < 2) {''',
    "onUpgrade v9 sessions.mode")

# 4. saveSession 加 mode 参数
rep('''    fun saveSession(title: String, msgs: List<SessionMsg>): Long {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val id = db.insert("sessions", null,
                    android.content.ContentValues().apply {
                        put("title", title)
                        put("updated_at", System.currentTimeMillis())
                    })''',
    '''    fun saveSession(title: String, msgs: List<SessionMsg>, mode: Int = 0): Long {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val id = db.insert("sessions", null,
                    android.content.ContentValues().apply {
                        put("title", title)
                        put("updated_at", System.currentTimeMillis())
                        put("mode", mode)
                    })''',
    "saveSession mode")

# 5. updateSession 加 mode 参数
rep('''    fun updateSession(id: Long, title: String, msgs: List<SessionMsg>) {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM session_msgs WHERE session_id = ?", arrayOf(id.toString()))
                db.execSQL(
                    "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?",
                    arrayOf(title, System.currentTimeMillis(), id)
                )''',
    '''    fun updateSession(id: Long, title: String, msgs: List<SessionMsg>, mode: Int = 0) {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM session_msgs WHERE session_id = ?", arrayOf(id.toString()))
                db.execSQL(
                    "UPDATE sessions SET title = ?, updated_at = ?, mode = ? WHERE id = ?",
                    arrayOf(title, System.currentTimeMillis(), mode, id)
                )''',
    "updateSession mode")

# 6. listSessions 按 mode 过滤
rep('''    fun listSessions(limit: Int = 20): List<SessionInfo> {
        lock.withLock {
            val out = mutableListOf<SessionInfo>()
            readableDatabase.rawQuery(
                "SELECT id, title, updated_at, pinned FROM sessions ORDER BY pinned DESC, updated_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->''',
    '''    fun listSessions(limit: Int = 20, mode: Int = 0): List<SessionInfo> {
        lock.withLock {
            val out = mutableListOf<SessionInfo>()
            readableDatabase.rawQuery(
                "SELECT id, title, updated_at, pinned FROM sessions WHERE mode = ? ORDER BY pinned DESC, updated_at DESC LIMIT ?",
                arrayOf(mode.toString(), limit.toString())
            ).use { c ->''',
    "listSessions mode filter")

io.open(path, "w", encoding="utf-8").write(d)
print("ALL DONE")
