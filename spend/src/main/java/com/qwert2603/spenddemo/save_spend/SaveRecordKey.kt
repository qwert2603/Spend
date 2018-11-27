package com.qwert2603.spenddemo.save_spend

import java.io.Serializable

sealed class SaveRecordKey : Serializable {
    data class EditRecord(val uuid: String) : SaveRecordKey()
    data class NewRecord(val recordTypeId: Long) : SaveRecordKey()
}