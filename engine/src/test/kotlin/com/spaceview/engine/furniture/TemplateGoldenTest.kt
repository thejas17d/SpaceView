package com.spaceview.engine.furniture

import com.spaceview.engine.Part
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

/**
 * Golden geometry of every **shipped** template id (T-D0 review MINOR 4).
 *
 * A stored furniture item is rebuilt from its template id and parameters whenever its model is stale, so the parts of
 * `sofa_v1` for a given parameter map must never change, and a parameter map that was valid when saved must stay valid.
 * This test pins a hash of the parts (names, faces to 1e-7 m, materials) for the defaults, every preset and one
 * boundary map per id. If it fails, do not update the hash: **ship a new id (`sofa_v2`) with the new look or ranges**
 * and keep the old one exactly as it is. Only a brand-new id gets its hashes added here.
 *
 * The hash covers the parts only, not the GLB bytes, so a GLB writer change (Builder A's) does not trip it.
 */
class TemplateGoldenTest {

    /** The pinned cases: the defaults, each preset, and W/D/H at their static minima and maxima (other values default). */
    private fun cases(t: FurnitureTemplate): List<Pair<String, Params>> {
        val (w, d, h) = t.params.take(3)
        return listOf("defaults" to emptyMap<String, Double>()) +
            t.presets.map { "preset:${it.id}" to it.values } +
            listOf("min" to mapOf(W to w.min, D to d.min, H to h.min), "max" to mapOf(W to w.max, D to d.max, H to h.max))
    }

    /** A stable text of the parts: name, material and the six faces rounded to 1e-7 m, in build order. */
    private fun canonical(parts: List<Part>): String = parts.joinToString("\n") { p ->
        fun f(v: Double) = String.format(Locale.ROOT, "%.7f", v + 0.0) // + 0.0 turns -0.0 into 0.0
        listOf(
            p.name, p.material.name,
            f(p.center.x - p.size.x / 2), f(p.center.x + p.size.x / 2),
            f(p.center.y - p.size.y / 2), f(p.center.y + p.size.y / 2),
            f(p.center.z - p.size.z / 2), f(p.center.z + p.size.z / 2),
            f(p.yawRad),
        ).joinToString(",")
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `shipped template geometry and validity never change - ship a new _vN instead`() {
        val actual = LinkedHashMap<String, String>()
        for (t in Templates.all) {
            for ((name, given) in cases(t)) {
                val p = t.resolve(given)
                assertEquals(
                    "${t.id} $name is no longer valid: stored items would show as a box. Ship a new _vN instead.",
                    emptyList<ParamError>(), t.validate(p),
                )
                actual["${t.id}/$name"] = sha256(canonical(t.build(p)))
            }
        }
        val missing = actual.keys - GOLDEN.keys
        val changed = actual.filter { (k, v) -> GOLDEN[k] != null && GOLDEN[k] != v }.keys
        val gone = GOLDEN.keys - actual.keys
        if (missing.isNotEmpty() || changed.isNotEmpty() || gone.isNotEmpty()) {
            fail(
                buildString {
                    if (changed.isNotEmpty()) append("Shipped geometry CHANGED for $changed: do not update the hash, ship a new _vN instead.\n")
                    if (gone.isNotEmpty()) append("Shipped template ids or presets REMOVED: $gone. Stored items need them.\n")
                    if (missing.isNotEmpty()) append("New cases without a golden hash (add them only for a brand-new id):\n")
                    missing.forEach { append("    \"").append(it).append("\" to \"").append(actual[it]).append("\",\n") }
                },
            )
        }
    }

    private companion object {
        /** Hashes of the shipped geometry (T milestone, 2026-11-16). Never edit an existing line. */
        val GOLDEN: Map<String, String> = mapOf(
        "sofa_v1/defaults" to "905bb2846add060bdb662f233c3f9caad7aeef0d5fbd3a084ff9209de227dd7c",
        "sofa_v1/preset:two_seat" to "c9f6678011a63088f2d3d6f6a39f0a3e0ac4b49c9026509fbcf5084ba2f26036",
        "sofa_v1/preset:three_seat" to "876bcea1e571c0af5f3d7dc1c7cd0186925a4012f1bf8a60afb6dee4d0d4d216",
        "sofa_v1/min" to "aa835907a92665bee5c920723c33a10a73a185d6a40a1965be5023820840a48a",
        "sofa_v1/max" to "581a46d43d33a4fd8b006feeedfa19e4835c3ff6a0e4febf2ecc858e2ade6cea",
        "bed_v1/defaults" to "5d5f6cfa471cd21ba151a4593fef0434740b1639920d1ca57724eb90099e2671",
        "bed_v1/preset:single" to "f9b209f430b440dc62c31487357e5bd479f3957c65504ecb3141fe51841fbce1",
        "bed_v1/preset:double" to "31583d2c6a8c17d4701813ebba32f37e9f530c80b4be18cce7745422ef7c8689",
        "bed_v1/preset:queen" to "c76e2c350eb08ccbbe1e576eff0e4a449759560fda99964ac14a1cfd285d31be",
        "bed_v1/preset:king" to "93522f70d3cbfdd4833aa1ccd6ef88066bf756108ad7dcff9c1e856f44589a86",
        "bed_v1/min" to "57623f8ae1e72f11cae88c6991fc1d0cf8e2f8e46ea7f328ac440080ac629c0b",
        "bed_v1/max" to "24f66cef1575e06181f73e398e47f5b38eccb569afed04871209fcfdf89dd98e",
        "wardrobe_v1/defaults" to "a367bf397fe1eb1f9b96dbd66223b4344e5684f8c355530682ba9d03663f5e60",
        "wardrobe_v1/preset:two_door" to "cc90e599217c290550ba85f3b9ff6bb3d38b755577801abe3662d1a492484d3e",
        "wardrobe_v1/preset:three_door" to "252ec1b335c90598dcaf1efb269b99324c5b8aacce2ac731d3b35cdf68efda71",
        "wardrobe_v1/min" to "71800627a5091c862ae4bfe351afe994c4f7894ae7a4595b37aba47f024bad87",
        "wardrobe_v1/max" to "2b4e9338045007642e73afef74247e54d3751b230646d9fd7f5b5142a3c897ec",
        "lsofa_v1/defaults" to "cb2fe86da8c9d822499391e94adb8c3aa3ca7871cf3ddf4ebb252cd6d1cdface",
        "lsofa_v1/preset:left" to "cddd5d96ab6f255e43fc92af772d385ec6a6ac0fc47f594d5204dd129bbba94e",
        "lsofa_v1/preset:right" to "cb2fe86da8c9d822499391e94adb8c3aa3ca7871cf3ddf4ebb252cd6d1cdface",
        "lsofa_v1/min" to "90041dcd9f8009f18afa8bfa07fb7c970c87d0ab71e90193753b3aa2cdc29697",
        "lsofa_v1/max" to "5c9e597168cfe028b1ebbf7e69609f0a38183a778ae641c4489b647d543c16fe",
        "dining_table_v1/defaults" to "69faf29e9c6270c4186290e0155ca8cada06e68fa04876d30f321fe514312718",
        "dining_table_v1/preset:four_seat" to "0eb9a968e494e318bdc31f8f99cb897239ad4f3a2d2d6cda4befb101156547b6",
        "dining_table_v1/preset:six_seat" to "80718372a6d2a4c5cd4a164557a895f1a926f66ef4171677261a4606c7759e48",
        "dining_table_v1/min" to "73d0b9c4d42d7e66ceed425a835bdea69bba1d494d5224fc474deef38f725433",
        "dining_table_v1/max" to "09f9978de7fcfda8e366345127797f07b411d4adb2cebc8af03b133cd2d771ee",
        "dining_chair_v1/defaults" to "2b2f43400ffe7b7e751fb5442803a1143c2cc90173dc32f9454fea830cd64c8d",
        "dining_chair_v1/min" to "eb75da1e17124fb8392ba020f432ed1a2b7e60356b6bf943feedb93ad39be874",
        "dining_chair_v1/max" to "31fa6ab626cbfb721105268eff630de7e523770a7c614450cff115a7af91429d",
        "tv_unit_v1/defaults" to "007e9459269ab9e71e471735e47c5133eb828510a5cb56813ebd983b2de6081f",
        "tv_unit_v1/min" to "2a415a964d40406e14b860dc5f1f8b91baf056ad4635b27f442f83ec8a446161",
        "tv_unit_v1/max" to "8f2ccd862645b7d43f8ec025ce3ce9e53b055149429fe824bee089a0b67dc91a",
        "bookshelf_v1/defaults" to "4e1e6045857355400867fbf136574cce01db7d4adad58b29d97af09c5b2e5b6e",
        "bookshelf_v1/min" to "de21e7019008b553105f5e4af1a842eddd7a09ac54a9b65b06b4d514fc6ed924",
        "bookshelf_v1/max" to "cb60592320b64eca7c76c13229da7e7474a35d034149d4676dcee728719fdfac",
        "study_table_v1/defaults" to "e63269de303189466abbaa15fbb4808aa582e0b6309eb59b6b767b2d01ce2fe9",
        "study_table_v1/min" to "b3a4947a7de38541b200c40186b44aa82d5482b094c0488b0550087947428c95",
        "study_table_v1/max" to "9e337c0dbf02c6dac4aefe4b311d79d5b8a90a11ad2b72f8f32403c477a70785",
        )
    }
}
