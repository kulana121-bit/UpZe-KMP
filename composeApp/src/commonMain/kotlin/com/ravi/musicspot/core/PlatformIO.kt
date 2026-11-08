package com.ravi.musicspot.core

expect object PlatformIO {
    fun appDir(): String
    fun cacheDir(): String
    fun readText(path: String): String?
    fun writeTextAtomic(path: String, text: String)
    fun writeBytes(path: String, bytes: ByteArray)
    fun appendBytes(path: String, bytes: ByteArray)
    fun rename(from: String, to: String): Boolean
    fun exists(path: String): Boolean
    fun delete(path: String): Boolean
    fun listFiles(dir: String): List<String>
    fun fileSize(path: String): Long
    fun lastModified(path: String): Long
    fun mkdirs(path: String): Boolean
}

class File(val path: String) {
    constructor(parent: File, child: String) : this(
        if (parent.path.isEmpty() || parent.path.endsWith("/")) "${parent.path}$child" else "${parent.path}/$child"
    )
    constructor(parentPath: String, child: String) : this(
        if (parentPath.isEmpty() || parentPath.endsWith("/")) "$parentPath$child" else "$parentPath/$child"
    )

    val name: String get() = path.substringAfterLast('/')
    val parentFile: File? get() = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { File(it) }
    val isFile: Boolean get() = PlatformIO.exists(path)
    val absolutePath: String get() = path
    fun readText(): String = PlatformIO.readText(path) ?: ""
    fun writeText(text: String) = PlatformIO.writeTextAtomic(path, text)
    fun writeBytes(bytes: ByteArray) = PlatformIO.writeBytes(path, bytes)
    fun appendBytes(bytes: ByteArray) = PlatformIO.appendBytes(path, bytes)
    fun delete(): Boolean = PlatformIO.delete(path)
    fun exists(): Boolean = PlatformIO.exists(path)
    val length: Long get() = PlatformIO.fileSize(path)
    fun lastModified(): Long = PlatformIO.lastModified(path)
    fun listFiles(): List<File> = PlatformIO.listFiles(path).map { File(this, it) }
    fun mkdirs(): Boolean = PlatformIO.mkdirs(path)
    fun renameTo(dest: File): Boolean = PlatformIO.rename(path, dest.path)

    override fun toString(): String = path
    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()
}
