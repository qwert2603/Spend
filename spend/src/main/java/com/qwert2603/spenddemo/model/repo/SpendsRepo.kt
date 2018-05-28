package com.qwert2603.spenddemo.model.repo

import android.arch.lifecycle.LiveData
import android.support.annotation.WorkerThread
import com.qwert2603.spenddemo.model.entity.CreatingSpend
import com.qwert2603.spenddemo.model.entity.Spend
import com.qwert2603.spenddemo.model.local_db.results.RecordResult

interface SpendsRepo {
    fun addSpend(creatingSpend: CreatingSpend)

    fun addSpends(spends: List<CreatingSpend>)

    fun editSpend(spend: Spend)

    fun removeSpend(spendId: Long)

    fun removeAllSpends()

    /**
     * Spends and Profits merged.
     * Must be sorted be date DECS.
     */
    fun getRecordsList(): LiveData<List<RecordResult>>

    fun locallyCreatedSpends(): LiveData<Spend>

    @WorkerThread
    fun getDumpText(): String

    fun get30DaysBalance(): LiveData<Long>
}

