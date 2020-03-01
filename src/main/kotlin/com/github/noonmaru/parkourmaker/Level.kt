package com.github.noonmaru.parkourmaker

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operation
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.session.ClipboardHolder
import org.bukkit.Bukkit
import org.bukkit.configuration.Configuration
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*


class Level {
    val name: String

    val file: File

    val region: CuboidRegion

    var clipboard: Clipboard? = null

    var challenge: Challenge? = null
        private set

    private val courseFile: File
        get() = File(ParkourMaker.coursesFolder, "$name.schem")

    constructor(name: String, region: CuboidRegion) {
        this.name = name
        this.region = region
        this.file = File(ParkourMaker.levelFolder, "$name.yml")
    }

    constructor(file: File) {
        this.name = file.name.removeSuffix(".yml")
        this.file = file

        YamlConfiguration.loadConfiguration(file).let { config ->
            val world = Bukkit.getWorld(config.getString("world")!!)!!
            val min = config.getBlockVector3("min")
            val max = config.getBlockVector3("max")
            region = CuboidRegion(BukkitAdapter.adapt(world), min, max)
        }
    }

    init {
        courseFile.run {
            if (exists()) {
                val format = ClipboardFormats.findByFile(this)!!
                clipboard = format.getReader(FileInputStream(this)).use { it.read() }
            }
        }
    }

    fun startChallenge() {
        check(challenge == null) { "Challenge is already in progress." }

        copyCourse()
        challenge = Challenge(this).apply {
            parseBlocks()
        }
    }

    private fun copyCourse() {
        this.clipboard = BlockArrayClipboard(region).apply {
            Operations.complete(
                ForwardExtentCopy(
                    WorldEdit.getInstance().editSessionFactory.getEditSession(
                        region.world,
                        -1
                    ), region, clipboard, region.minimumPoint
                ).apply {
                    isCopyingEntities = true
                })
        }
    }

    fun stopChallenge() {
        challenge.let {
            checkNotNull(it) { "Challenge is not in progress." }
            challenge = null
            it.destroy()
            clipboard?.paste()
        }
    }

    private fun Clipboard.save() {
        val file = courseFile
        val temp = File(file.parent, "${file.name}.tmp")

        BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(FileOutputStream(temp))
            .use { writer -> writer.write(clipboard) }

        if (file.exists()) {
            if (file.md5Digest.contentEquals(temp.md5Digest)) {
                Files.copy(
                    file.toPath(),
                    File(ParkourMaker.historyFolder, "$name${dateFormat.format(Date())}.schem").toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            file.delete()
        }

        temp.renameTo(file)
        temp.delete()
    }

    private fun Clipboard.paste() {
        val mp = region.minimumPoint

        WorldEdit.getInstance().editSessionFactory.getEditSession(region.world, -1).use { editSession ->
            val operation: Operation = ClipboardHolder(clipboard)
                .createPaste(editSession)
                .to(BlockVector3.at(mp.x, mp.y, mp.z))
                .ignoreAirBlocks(false)
                .build()
            Operations.complete(operation)
        }
    }

    fun save() {
        val config = YamlConfiguration()
        region.let { region ->
            config.set("world", region.world!!.name)
            config.setPoint("min", region.minimumPoint)
            config.setPoint("max", region.maximumPoint)
        }

        file.parentFile.mkdirs()
        config.save(file)
    }
}

private val File.md5Digest: ByteArray
    get() {
        val md = MessageDigest.getInstance("MD5")
        DigestInputStream(FileInputStream(this).buffered(), md).use {
            while (true) {
                if (it.read() == -1)
                    break
            }
        }
        return md.digest()
    }

private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss")

private fun ConfigurationSection.getBlockVector3(path: String): BlockVector3 {
    return getConfigurationSection("path").run {
        BlockVector3.at(
            getInt("x"),
            getInt("y"),
            getInt("z"))
    }
}

private fun ConfigurationSection.setPoint(path: String, point: BlockVector3) {
    createSection(path).apply {
        this["x"] = point.blockX
        this["y"] = point.blockY
        this["z"] = point.blockZ
    }
}