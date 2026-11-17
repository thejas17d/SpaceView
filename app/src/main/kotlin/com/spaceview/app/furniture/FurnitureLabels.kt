package com.spaceview.app.furniture

import androidx.annotation.StringRes
import com.spaceview.app.R
import com.spaceview.engine.furniture.BedTemplate
import com.spaceview.engine.furniture.BookshelfTemplate
import com.spaceview.engine.furniture.DiningChairTemplate
import com.spaceview.engine.furniture.DiningTableTemplate
import com.spaceview.engine.furniture.FurnitureParams
import com.spaceview.engine.furniture.FurnitureTemplate
import com.spaceview.engine.furniture.LSofaTemplate
import com.spaceview.engine.furniture.SofaTemplate
import com.spaceview.engine.furniture.StudyTableTemplate
import com.spaceview.engine.furniture.TvUnitTemplate
import com.spaceview.engine.furniture.WardrobeTemplate

/** String resources for templates, parameters, options, presets and palettes (ids → copy). */
object FurnitureLabels {

    @StringRes
    fun name(t: FurnitureTemplate): Int = when (t) {
        SofaTemplate -> R.string.furniture_t_sofa
        LSofaTemplate -> R.string.furniture_t_lsofa
        BedTemplate -> R.string.furniture_t_bed
        WardrobeTemplate -> R.string.furniture_t_wardrobe
        DiningTableTemplate -> R.string.furniture_t_dining_table
        DiningChairTemplate -> R.string.furniture_t_dining_chair
        TvUnitTemplate -> R.string.furniture_t_tv_unit
        BookshelfTemplate -> R.string.furniture_t_bookshelf
        StudyTableTemplate -> R.string.furniture_t_study_table
        else -> R.string.furniture_title
    }

    @StringRes
    fun detail(t: FurnitureTemplate): Int = when (t) {
        SofaTemplate -> R.string.furniture_t_sofa_detail
        LSofaTemplate -> R.string.furniture_t_lsofa_detail
        BedTemplate -> R.string.furniture_t_bed_detail
        WardrobeTemplate -> R.string.furniture_t_wardrobe_detail
        DiningTableTemplate -> R.string.furniture_t_dining_table_detail
        DiningChairTemplate -> R.string.furniture_t_dining_chair_detail
        TvUnitTemplate -> R.string.furniture_t_tv_unit_detail
        BookshelfTemplate -> R.string.furniture_t_bookshelf_detail
        StudyTableTemplate -> R.string.furniture_t_study_table_detail
        else -> R.string.furniture_picker_intro
    }

    /** The label of parameter [id] on template [t] (a bed's D is its length). */
    @StringRes
    fun param(t: FurnitureTemplate, id: String): Int = when (id) {
        FurnitureTemplate.W -> R.string.furniture_p_width
        FurnitureTemplate.D -> if (t == BedTemplate) R.string.furniture_p_length else R.string.furniture_p_depth
        FurnitureTemplate.H -> R.string.furniture_p_height
        FurnitureParams.SEATS -> R.string.furniture_p_seats
        FurnitureParams.SIDE -> R.string.furniture_p_side
        FurnitureParams.LEG -> R.string.furniture_p_leg_height
        FurnitureParams.SEAT -> R.string.furniture_p_seat_height
        FurnitureParams.ARM_H -> R.string.furniture_p_arm_height
        FurnitureParams.ARM_W -> R.string.furniture_p_arm_width
        FurnitureParams.BACK_T -> R.string.furniture_p_back_thickness
        FurnitureParams.MAIN_D -> R.string.furniture_p_main_depth
        FurnitureParams.RETURN_W -> R.string.furniture_p_return_width
        FurnitureParams.HEADBOARD -> R.string.furniture_p_headboard
        FurnitureParams.BED_HEIGHT -> R.string.furniture_p_bed_height
        FurnitureParams.MATTRESS -> R.string.furniture_p_mattress
        FurnitureParams.HEADBOARD_T -> R.string.furniture_p_headboard_thickness
        FurnitureParams.DOORS -> R.string.furniture_p_doors
        FurnitureParams.PLINTH -> R.string.furniture_p_plinth
        FurnitureParams.TOP_T -> R.string.furniture_p_top_thickness
        FurnitureParams.LEG_SIZE -> R.string.furniture_p_leg_size
        FurnitureParams.LEG_INSET -> R.string.furniture_p_leg_inset
        FurnitureParams.SEAT_T -> R.string.furniture_p_seat_thickness
        FurnitureParams.COMPARTMENTS -> R.string.furniture_p_compartments
        FurnitureParams.SHELVES -> R.string.furniture_p_shelves
        FurnitureParams.PEDESTAL -> R.string.furniture_p_pedestal
        FurnitureParams.PEDESTAL_W -> R.string.furniture_p_pedestal_width
        else -> R.string.furniture_details
    }

    /** How to measure parameter [id] on template [t] (only measured parameters have one). */
    @StringRes
    fun hint(t: FurnitureTemplate, id: String): Int? = when (id) {
        FurnitureTemplate.W -> R.string.furniture_h_width
        FurnitureTemplate.D -> when (t) {
            BedTemplate -> R.string.furniture_h_length
            LSofaTemplate -> R.string.furniture_h_depth_lsofa
            else -> R.string.furniture_h_depth
        }
        FurnitureTemplate.H -> R.string.furniture_h_height
        FurnitureParams.SEAT -> R.string.furniture_h_seat_height
        FurnitureParams.ARM_H -> R.string.furniture_h_arm_height
        FurnitureParams.ARM_W -> R.string.furniture_h_arm_width
        FurnitureParams.MAIN_D -> R.string.furniture_h_main_depth
        FurnitureParams.RETURN_W -> R.string.furniture_h_return_width
        FurnitureParams.BED_HEIGHT -> R.string.furniture_h_bed_height
        FurnitureParams.MATTRESS -> R.string.furniture_h_mattress
        FurnitureParams.PLINTH -> R.string.furniture_h_plinth
        FurnitureParams.LEG_INSET -> R.string.furniture_h_leg_inset
        FurnitureParams.LEG -> R.string.furniture_h_leg_height
        FurnitureParams.PEDESTAL_W -> R.string.furniture_h_pedestal_width
        else -> null
    }

    /** Copy for option [value] of a choice parameter [id]; null means "show the number" (counts). */
    @StringRes
    fun option(id: String, value: Int): Int? = when (id) {
        FurnitureParams.SIDE -> if (value == FurnitureParams.SIDE_LEFT) R.string.furniture_o_left else R.string.furniture_o_right
        FurnitureParams.HEADBOARD -> if (value == 1) R.string.furniture_o_yes else R.string.furniture_o_no
        FurnitureParams.PEDESTAL -> when (value) {
            FurnitureParams.PEDESTAL_LEFT -> R.string.furniture_o_left
            FurnitureParams.PEDESTAL_RIGHT -> R.string.furniture_o_right
            else -> R.string.furniture_o_none
        }
        else -> null
    }

    @StringRes
    fun preset(id: String): Int = when (id) {
        "two_seat" -> R.string.furniture_preset_two_seat
        "three_seat" -> R.string.furniture_preset_three_seat
        "left" -> R.string.furniture_preset_left
        "right" -> R.string.furniture_preset_right
        "single" -> R.string.furniture_preset_single
        "double" -> R.string.furniture_preset_double
        "queen" -> R.string.furniture_preset_queen
        "king" -> R.string.furniture_preset_king
        "two_door" -> R.string.furniture_preset_two_door
        "three_door" -> R.string.furniture_preset_three_door
        "four_seat" -> R.string.furniture_preset_four_seat
        "six_seat" -> R.string.furniture_preset_six_seat
        else -> R.string.furniture_start_from
    }

    @StringRes
    fun palette(index: Int): Int = when (index) {
        1 -> R.string.furniture_palette_walnut
        2 -> R.string.furniture_palette_indigo
        3 -> R.string.furniture_palette_oak
        4 -> R.string.furniture_palette_charcoal
        else -> R.string.furniture_palette_stone
    }
}
