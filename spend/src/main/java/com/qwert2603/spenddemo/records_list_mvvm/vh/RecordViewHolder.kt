package com.qwert2603.spenddemo.records_list_mvvm.vh

import android.support.v4.widget.TextViewCompat
import android.util.TypedValue
import android.view.ViewGroup
import com.qwert2603.andrlib.util.color
import com.qwert2603.andrlib.util.setVisible
import com.qwert2603.spenddemo.R
import com.qwert2603.spenddemo.model.entity.Record
import com.qwert2603.spenddemo.records_list_mvvm.RecordsListAdapter
import com.qwert2603.spenddemo.utils.Const
import com.qwert2603.spenddemo.utils.DateTimeTextViews
import com.qwert2603.spenddemo.utils.setStrike
import com.qwert2603.spenddemo.utils.toPointedString
import kotlinx.android.synthetic.main.item_record.view.*

class RecordViewHolder(parent: ViewGroup) : BaseViewHolder<Record>(parent, R.layout.item_record) {

    init {
        //todo:????
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                itemView.time_TextView,
                14,
                16,
                1,
                TypedValue.COMPLEX_UNIT_SP
        )
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                itemView.date_TextView,
                14,
                16,
                1,
                TypedValue.COMPLEX_UNIT_SP
        )
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                itemView.kind_TextView,
                8,
                18,
                1,
                TypedValue.COMPLEX_UNIT_SP
        )
    }

    override fun bind(t: Record, adapter: RecordsListAdapter) = with(itemView) {
        super.bind(t, adapter)

        local_ImageView.setVisible(adapter.showChangeKinds)
        date_TextView.setVisible(adapter.showDatesInRecords)

        local_ImageView.setImageResource(when {
            t.uuid in adapter.syncingRecordsUuids -> R.drawable.ic_local
            t.change != null -> R.drawable.ic_syncing
            else -> R.drawable.ic_done_24dp
        })
        if (t.change != null) {
            local_ImageView.setColorFilter(resources.color(when (t.change.changeKindId) {
                Const.CHANGE_KIND_UPSERT -> R.color.local_change_edit
                Const.CHANGE_KIND_DELETE -> R.color.local_change_delete
                else -> null!!
            }))
        } else {
            local_ImageView.setColorFilter(resources.color(R.color.anth))
        }
        DateTimeTextViews.render(
                dateTextView = date_TextView,
                timeTextView = time_TextView,
                date = t.date,
                time = t.time,
                showTimeAtAll = adapter.showTimesInRecords
        )
        kind_TextView.text = t.kind
        kind_TextView.setTextColor(resources.color(when (t.recordTypeId) {
            Const.RECORD_TYPE_ID_SPEND -> R.color.spend
            Const.RECORD_TYPE_ID_PROFIT -> R.color.profit
            else -> null!!
        }))
        value_TextView.text = t.value.toPointedString()

        isClickable = t.change?.changeKindId != Const.CHANGE_KIND_DELETE
        isLongClickable = t.change?.changeKindId != Const.CHANGE_KIND_DELETE

        val strike = t.change?.changeKindId == Const.CHANGE_KIND_DELETE
        listOf(date_TextView, time_TextView, kind_TextView, value_TextView)
                .forEach { it.setStrike(strike) }
    }
}