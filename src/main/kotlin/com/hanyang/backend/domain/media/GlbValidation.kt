package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

/** Bounded static GLB subset used by our exporter and viewer. This is not a full glTF conformance validator. */
internal class GlbValidation(private val mapper: JsonMapper) {
    private fun invalid(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "파일 내용과 형식을 확인해 주세요.")
    private data class View(val offset: Long, val length: Long, val stride: Long?)
    private data class Access(val count: Long, val components: Int, val componentType: Int, val view: View, val offset: Long, val stride: Long)
    private fun number(node: JsonNode, name: String, default: Long? = null): Long {
        val value = node.path(name)
        if (value.isMissingNode && default != null) return default
        if (!value.isIntegralNumber || !value.canConvertToLong()) invalid()
        return value.asLong()
    }
    private fun array(node: JsonNode, name: String, max: Int): List<JsonNode> {
        val value = node.path(name)
        if (value.isMissingNode) return emptyList()
        if (!value.isArray || value.size() > max) invalid()
        return value.toList()
    }
    private fun index(value: JsonNode, size: Int): Int {
        if (!value.isIntegralNumber || !value.canConvertToInt()) invalid()
        return value.asInt().also { if (it !in 0 until size) invalid() }
    }
    fun validate(bytes: ByteArray) {
        try {
            if (bytes.size < 20) invalid()
            val reader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (reader.int != 0x46546c67 || reader.int != 2 || reader.int != bytes.size) invalid()
            var json: ByteArray? = null
            var binStart = -1
            var binLength = 0
            var chunk = 0
            while (reader.hasRemaining()) {
                if (reader.remaining() < 8) invalid()
                val length = reader.int; val type = reader.int
                if (length < 0 || length % 4 != 0 || length > reader.remaining()) invalid()
                when (type) {
                    0x4e4f534a -> {
                        if (chunk != 0 || json != null || length > 1_048_576) invalid()
                        json = ByteArray(length).also { reader.get(it) }
                    }
                    0x004e4942 -> {
                        if (chunk != 1 || binStart >= 0) invalid()
                        binStart = reader.position(); binLength = length
                        reader.position(reader.position() + length)
                    }
                    else -> invalid()
                }
                chunk++
            }
            val tree = mapper.readTree(json ?: invalid())
            if (!tree.isObject || tree.path("asset").path("version").asText() != "2.0") invalid()
            // Decompression, sparse arrays, animation and skinning are outside this static property viewer's contract.
            if (array(tree, "animations", 0).isNotEmpty() || array(tree, "skins", 0).isNotEmpty()) invalid()
            val allowedExtensions = setOf("KHR_materials_unlit", "KHR_texture_transform", "KHR_materials_emissive_strength", "KHR_materials_ior", "KHR_materials_specular", "KHR_materials_clearcoat", "KHR_materials_sheen", "KHR_materials_transmission", "KHR_materials_volume", "KHR_materials_iridescence")
            for (key in listOf("extensionsUsed", "extensionsRequired"))
                if (array(tree, key, 16).any { !it.isString || it.asText() !in allowedExtensions }) invalid()
            val pending = ArrayDeque<JsonNode>(); pending.add(tree)
            while (pending.isNotEmpty()) {
                val value = pending.removeLast()
                if (value.isNumber && !value.asDouble().isFinite()) invalid()
                if (value.isObject) {
                    for ((key, child) in value.properties()) {
                        if (key == "extensions" && (!child.isObject || child.properties().any { it.key !in allowedExtensions })) invalid()
                        pending.add(child)
                    }
                } else if (value.isArray) value.forEach(pending::add)
            }
            val buffers = array(tree, "buffers", 1)
            if (buffers.size != 1 || buffers[0].has("uri") || binStart < 0) invalid()
            val bufferLength = number(buffers[0], "byteLength")
            if (bufferLength < 1 || bufferLength > binLength || binLength - bufferLength > 3) invalid()
            val views = array(tree, "bufferViews", 4096).map {
                if (number(it, "buffer") != 0L) invalid()
                val offset = number(it, "byteOffset", 0); val length = number(it, "byteLength")
                val stride = if (it.has("byteStride")) number(it, "byteStride") else null
                if (offset < 0 || length < 1 || offset > bufferLength || length > bufferLength - offset ||
                    (stride != null && (stride !in 4..252 || stride % 4 != 0L))) invalid()
                View(offset, length, stride)
            }
            var decodedBytes = 0L
            val accessors = array(tree, "accessors", 2048).map {
                if (it.has("sparse") || !it.has("bufferView")) invalid()
                val view = views[index(it.path("bufferView"), views.size)]
                val count = number(it, "count"); val offset = number(it, "byteOffset", 0)
                val componentType = number(it, "componentType").also { value -> if (value !in setOf(5120L,5121L,5122L,5123L,5125L,5126L)) invalid() }.toInt()
                val componentBytes = when (componentType) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> invalid() }
                val components = when (it.path("type").asText()) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> invalid() }
                val elementBytes = components * componentBytes
                val stride = view.stride ?: elementBytes.toLong()
                if (count !in 1..1_000_000 || offset < 0 || offset % componentBytes != 0L || (view.offset + offset) % componentBytes != 0L ||
                    stride < elementBytes || offset > view.length || (count - 1) * stride + elementBytes > view.length - offset) invalid()
                decodedBytes += count * elementBytes
                if (decodedBytes > 64 * 1024 * 1024) invalid()
                if (componentType == 5126) {
                    for (item in 0 until count) for (component in 0 until components) {
                        val at = binStart + view.offset + offset + item * stride + component * 4
                        val value = reader.getFloat(at.toInt())
                        if (!value.isFinite() || kotlin.math.abs(value.toDouble()) > 1_000_000_000_000) invalid()
                    }
                }
                Access(count, components, componentType, view, offset, stride)
            }
            val maximumIndices = mutableMapOf<Int,Long>()
            var primitiveCount = 0
            var meshDrawVertices = 0L
            val meshes = array(tree, "meshes", 512)
            val drawVerticesPerMesh = meshes.map { mesh ->
                val primitives = array(mesh, "primitives", 2048)
                if (primitives.isEmpty()) invalid()
                primitives.sumOf { primitive ->
                    if (++primitiveCount > 2048 || primitive.has("targets") || number(primitive, "mode", 4) !in 0..6) invalid()
                    val attributes = primitive.path("attributes")
                    if (!attributes.isObject || !attributes.has("POSITION")) invalid()
                    val position = accessors[index(attributes.path("POSITION"), accessors.size)]
                    if (position.componentType != 5126 || position.components != 3) invalid()
                    for ((_, ref) in attributes.properties()) if (accessors[index(ref, accessors.size)].count != position.count) invalid()
                    val drawCount = if (primitive.has("indices")) accessors[index(primitive.path("indices"),accessors.size)].count else position.count
                    meshDrawVertices += drawCount
                    if (meshDrawVertices > 4_000_000) invalid()
                    if (primitive.has("indices")) {
                        val accessorId = index(primitive.path("indices"), accessors.size)
                        val indices = accessors[accessorId]
                        if (indices.components != 1 || indices.componentType !in setOf(5121, 5123, 5125)) invalid()
                        val maximum = maximumIndices.getOrPut(accessorId) {
                            var max = 0L
                            for (item in 0 until indices.count) {
                                val at = (binStart + indices.view.offset + indices.offset + item * indices.stride).toInt()
                                val ref = when (indices.componentType) { 5121 -> reader.get(at).toLong() and 255; 5123 -> reader.getShort(at).toLong() and 65535; else -> reader.getInt(at).toLong() and 0xffffffffL }
                                max = maxOf(max,ref)
                            }
                            max
                        }
                        if (maximum >= position.count) invalid()
                    }
                    drawCount
                }
            }
            val nodes = array(tree, "nodes", 2048)
            val parents = IntArray(nodes.size)
            val children = nodes.map { node ->
                if (node.has("mesh")) index(node.path("mesh"), meshes.size)
                if (node.has("skin")) invalid()
                if (node.has("matrix") && listOf("translation","rotation","scale").any(node::has)) invalid()
                for ((name,length) in listOf("matrix" to 16,"translation" to 3,"rotation" to 4,"scale" to 3)) {
                    if (!node.has(name)) continue
                    val values = array(node,name,length)
                    if (values.size != length || values.any { !it.isNumber || kotlin.math.abs(it.asDouble()) > 10_000_000 }) invalid()
                    if (name == "rotation" && kotlin.math.abs(values.sumOf { it.asDouble()*it.asDouble() }-1) > 0.01) invalid()
                    if (name == "matrix" && (listOf(3,7,11).any { kotlin.math.abs(values[it].asDouble()) > 0.000001 } || kotlin.math.abs(values[15].asDouble()-1)>0.000001)) invalid()
                }
                val kids = array(node, "children", 2048).map { index(it, nodes.size) }
                if (kids.distinct().size != kids.size) invalid()
                for (child in kids) if (++parents[child] > 1) invalid()
                kids
            }
            val visited = IntArray(nodes.size)
            val heights = IntArray(nodes.size)
            fun visit(id: Int, depth: Int): Int {
                if (depth > 128) invalid()
                if (visited[id] == 1) invalid()
                if (visited[id] == 2) return heights[id]
                visited[id] = 1
                val height = (children[id].maxOfOrNull { visit(it, depth + 1) } ?: -1) + 1
                if (height > 128) invalid()
                visited[id] = 2; heights[id] = height
                return height
            }
            nodes.indices.forEach { visit(it, 0) }
            val scenes = array(tree, "scenes", 32)
            if (scenes.isEmpty()) invalid()
            val selected = if (tree.has("scene")) index(tree.path("scene"), scenes.size) else 0
            fun localMatrix(node: JsonNode): DoubleArray {
                if (node.has("matrix")) return node.path("matrix").map { it.asDouble() }.toDoubleArray()
                fun vector(name: String, defaults: DoubleArray) = if (node.has(name)) node.path(name).map { it.asDouble() }.toDoubleArray() else defaults
                val t = vector("translation",doubleArrayOf(0.0,0.0,0.0))
                val q = vector("rotation",doubleArrayOf(0.0,0.0,0.0,1.0))
                val scale = vector("scale",doubleArrayOf(1.0,1.0,1.0))
                val x=q[0];val y=q[1];val z=q[2];val w=q[3]
                val xx=x*x*2;val yy=y*y*2;val zz=z*z*2
                val xy=x*y*2;val xz=x*z*2;val yz=y*z*2
                val wx=w*x*2;val wy=w*y*2;val wz=w*z*2
                return doubleArrayOf((1-yy-zz)*scale[0],(xy+wz)*scale[0],(xz-wy)*scale[0],0.0,
                    (xy-wz)*scale[1],(1-xx-zz)*scale[1],(yz+wx)*scale[1],0.0,
                    (xz+wy)*scale[2],(yz-wx)*scale[2],(1-xx-yy)*scale[2],0.0,t[0],t[1],t[2],1.0)
            }
            var scenePositions = 0L
            fun sceneGeometry(id: Int, parent: DoubleArray) {
                val node = nodes[id]; val local = localMatrix(node)
                val world = DoubleArray(16) { at ->
                    (0..3).sumOf { k -> parent[k*4+at%4] * local[at/4*4+k] }.also {
                        if (!it.isFinite() || kotlin.math.abs(it) > 1_000_000_000_000) invalid()
                    }
                }
                if (node.has("mesh")) scenePositions += drawVerticesPerMesh[index(node.path("mesh"), meshes.size)]
                if (scenePositions > 4_000_000) invalid()
                children[id].forEach { sceneGeometry(it,world) }
            }
            val identity = DoubleArray(16) { if (it%5==0) 1.0 else 0.0 }
            for ((sceneIndex,scene) in scenes.withIndex()) {
                val roots = array(scene,"nodes",2048).map { index(it,nodes.size) }
                if (roots.distinct().size != roots.size || roots.any { parents[it] != 0 }) invalid()
                val before = scenePositions
                roots.forEach { sceneGeometry(it,identity) }
                if (sceneIndex == selected && before == scenePositions) invalid()
            }
            array(tree, "materials", 512)
            array(tree, "samplers", 64)
            array(tree, "cameras", 32)
            var totalPixels = 0L
            val imagePixels = mutableListOf<Long>()
            for (image in array(tree, "images", 16)) {
                if (image.has("uri") || image.path("mimeType").asText() !in setOf("image/png", "image/jpeg")) invalid()
                val view = views[index(image.path("bufferView"), views.size)]
                if (view.length > 5 * 1024 * 1024) invalid()
                val data = bytes.copyOfRange((binStart + view.offset).toInt(), (binStart + view.offset + view.length).toInt())
                ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { input ->
                    val imageReaders = ImageIO.getImageReaders(input)
                    if (!imageReaders.hasNext()) invalid()
                    val imageReader = imageReaders.next()
                    try {
                        imageReader.input = input
                        val width = imageReader.getWidth(0); val height = imageReader.getHeight(0)
                        val pixels = width.toLong() * height
                        imagePixels.add(pixels)
                        totalPixels += pixels
                        if (width !in 1..8192 || height !in 1..8192 || totalPixels > 12_000_000 || imageReader.read(0) == null) invalid()
                    } finally { imageReader.dispose() }
                }
            }
            var texturePixels = 0L
            for (texture in array(tree, "textures", 64)) {
                texturePixels += imagePixels[index(texture.path("source"), imagePixels.size)]
                if (texturePixels > 12_000_000) invalid()
            }
        } catch (failure: ApiException) { throw failure } catch (_: Exception) { invalid() }
    }
}
