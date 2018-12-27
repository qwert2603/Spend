package com.qwert2603.spenddemo.model.entity

import com.qwert2603.andrlib.util.Const
import java.io.Serializable

sealed class Interval {
    abstract fun minutes(): Int
}


data class Days(val days: Int) : Interval(), Serializable {
    override fun minutes() = days * Const.MINUTES_PER_DAY
}

val Int.days: Days get() = Days(this)
operator fun Days.unaryMinus() = Days(-days)


data class Minutes(val minutes: Int) : Interval(), Serializable {
    override fun minutes() = minutes
}

val Int.minutes: Minutes get() = Minutes(this)
operator fun Minutes.unaryMinus() = Minutes(-minutes)