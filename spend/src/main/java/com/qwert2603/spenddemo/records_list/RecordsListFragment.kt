package com.qwert2603.spenddemo.records_list

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import android.view.animation.AnimationUtils
import com.hannesdorfmann.fragmentargs.annotation.Arg
import com.hannesdorfmann.fragmentargs.annotation.FragmentWithArgs
import com.jakewharton.rxbinding2.support.v7.widget.RxRecyclerView
import com.jakewharton.rxbinding2.view.RxView
import com.qwert2603.andrlib.base.mvi.BaseFragment
import com.qwert2603.andrlib.base.mvi.ViewAction
import com.qwert2603.andrlib.util.LogUtils
import com.qwert2603.andrlib.util.color
import com.qwert2603.andrlib.util.setVisible
import com.qwert2603.spenddemo.R
import com.qwert2603.spenddemo.change_records.ChangeRecordsDialogFragment
import com.qwert2603.spenddemo.change_records.ChangeRecordsDialogFragmentBuilder
import com.qwert2603.spenddemo.di.DIHolder
import com.qwert2603.spenddemo.dialogs.*
import com.qwert2603.spenddemo.env.E
import com.qwert2603.spenddemo.model.entity.*
import com.qwert2603.spenddemo.navigation.BackPressListener
import com.qwert2603.spenddemo.navigation.KeyboardManager
import com.qwert2603.spenddemo.records_list.vh.DaySumViewHolder
import com.qwert2603.spenddemo.save_record.SaveRecordDialogFragmentBuilder
import com.qwert2603.spenddemo.save_record.SaveRecordKey
import com.qwert2603.spenddemo.utils.*
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.android.synthetic.main.fragment_records_list.*
import java.util.concurrent.TimeUnit

@FragmentWithArgs
class RecordsListFragment : BaseFragment<RecordsListViewState, RecordsListView, RecordsListPresenter>(), RecordsListView, BackPressListener {

    companion object {
        private const val REQUEST_CHOOSE_LONG_SUM_PERIOD = 6
        private const val REQUEST_CHOOSE_SHORT_SUM_PERIOD = 7
        private const val REQUEST_ASK_FOR_RECORD_ACTIONS = 8

        private var layoutAnimationShown = false
    }

    @Arg
    lateinit var key: RecordsListKey

    override fun createPresenter() = DIHolder.diManager.presentersCreatorComponent
            .recordsListPresenterCreatorComponent()
            .build()
            .createRecordsListPresenter()

    private var initialScrollDone by BundleBoolean("initialScrollDone", { arguments!! }, false)

    private val adapter: RecordsListAdapter get() = records_RecyclerView.adapter as RecordsListAdapter
    private val itemAnimator: RecordsListAnimator get() = records_RecyclerView.itemAnimator as RecordsListAnimator
    private val layoutManager: LinearLayoutManager get() = records_RecyclerView.layoutManager as LinearLayoutManager

    private val menuHolder = MenuHolder()
    private val selectModeMenuHolder = MenuHolder()

    private val editRecordClicks = PublishSubject.create<String>()
    private val deleteRecordClicks = PublishSubject.create<String>()

    private val longSumPeriodSelected = PublishSubject.create<Days>()
    private val shortSumPeriodSelected = PublishSubject.create<Minutes>()

    private val cancelSelection = PublishSubject.create<Any>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments = arguments ?: Bundle()
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_records_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selectPanel_LinearLayout.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        requireActivity().menuInflater.inflate(R.menu.records_select, select_ActionMenuView.menu)
        selectModeMenuHolder.menu = select_ActionMenuView.menu

        records_RecyclerView.adapter = RecordsListAdapter(isDaySumsClickable = false)
        records_RecyclerView.recycledViewPool.setMaxRecycledViews(RecordsListAdapter.VIEW_TYPE_RECORD, 30)
        records_RecyclerView.recycledViewPool.setMaxRecycledViews(RecordsListAdapter.VIEW_TYPE_DATE_SUM, 20)
        records_RecyclerView.addItemDecoration(ConditionDividerDecoration(requireContext()) { rv, vh, _ ->
            vh.adapterPosition > 0 && rv.findViewHolderForAdapterPosition(vh.adapterPosition - 1) is DaySumViewHolder
        })
        records_RecyclerView.itemAnimator = RecordsListAnimator(createSpendViewImpl)

        createSpendViewImpl.dialogShower = object : DialogAwareView.DialogShower {
            override fun showDialog(dialogFragment: DialogFragment, requestCode: Int) {
                dialogFragment
                        .makeShow(requestCode)
            }
        }

        Observable
                .combineLatest(
                        RxRecyclerView.scrollEvents(records_RecyclerView)
                                .switchMap {
                                    Observable.interval(0, 1, TimeUnit.SECONDS)
                                            .take(2)
                                            .map { it == 0L }
                                }
                                .distinctUntilChanged()
                                .observeOn(AndroidSchedulers.mainThread()),
                        RxRecyclerView.scrollEvents(records_RecyclerView)
                                .map {
                                    val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                                    val lastIsTotal = lastVisiblePosition != RecyclerView.NO_POSITION
                                            && currentViewState.records?.get(lastVisiblePosition) is Totals
                                    return@map !lastIsTotal
                                },
                        Boolean::and.toRxBiFunction()
                )
                .subscribe { showFromScroll ->
                    val showFloatingDate = currentViewState.showInfo.showFloatingDate()
                    val records = currentViewState.records
                    floatingDate_TextView.setVisible(showFromScroll && showFloatingDate && records?.any { it is DaySum } == true)
                }
                .disposeOnDestroyView()
        RxRecyclerView.scrollEvents(records_RecyclerView)
                .subscribe {
                    if (!currentViewState.showInfo.showFloatingDate()) return@subscribe
                    var i = layoutManager.findLastVisibleItemPosition()
                    val floatingCenter = floatingDate_TextView.getGlobalVisibleRectRightNow().centerY()
                    val viewHolder = records_RecyclerView.findViewHolderForAdapterPosition(i)
                    val records = currentViewState.records
                    if (viewHolder == null || records == null) {
                        floatingDate_TextView.text = ""
                        return@subscribe
                    }
                    val vhTop = viewHolder.itemView.getGlobalVisibleRectRightNow().top
                    if (i > 0 && vhTop < floatingCenter) --i
                    if (i in 1..records.lastIndex && records[i] is Totals) --i
                    i = records.indexOfFirst(startIndex = i) { it is DaySum }
                    if (i >= 0) {
                        floatingDate_TextView.text = (records[i] as DaySum).day.toFormattedString(resources, stringMonth = true)
                    } else {
                        floatingDate_TextView.text = ""
                    }
                }
                .disposeOnDestroyView()

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        selectModeMenuHolder.menu = null
        super.onDestroyView()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        createSpendViewImpl.onDialogResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK && data != null) {
            when (requestCode) {
                REQUEST_CHOOSE_LONG_SUM_PERIOD -> data.getSerializableExtra(ChooseLongSumPeriodDialog.DAYS_KEY)
                        .let { it as? Days }
                        ?.also { longSumPeriodSelected.onNext(it) }
                REQUEST_CHOOSE_SHORT_SUM_PERIOD -> data.getSerializableExtra(ChooseShortSumPeriodDialog.MINUTES_KEY)
                        .let { it as? Minutes }
                        ?.also { shortSumPeriodSelected.onNext(it) }
                REQUEST_ASK_FOR_RECORD_ACTIONS -> data.getSerializableExtra(RecordActionsDialogFragment.RESULT_KEY)
                        .let { it as? RecordActionsDialogFragment.Result }
                        ?.also {
                            when (it.action) {
                                RecordActionsDialogFragment.Result.Action.EDIT -> editRecordClicks
                                RecordActionsDialogFragment.Result.Action.DELETE -> deleteRecordClicks
                            }.onNext(it.recordUuid)
                        }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.records_list, menu)

        menu.findItem(R.id.show_change_kinds).isVisible = E.env.syncWithServer
        menu.findItem(R.id.add_stub_records).isVisible = E.env.buildForTesting()
        menu.findItem(R.id.clear_all).isVisible = E.env.buildForTesting()

        menuHolder.menu = menu
        renderAll()
    }

    override fun onDestroyOptionsMenu() {
        menuHolder.menu = null
        super.onDestroyOptionsMenu()
    }

    override fun recordClicks(): Observable<Record> = adapter.itemClicks.mapNotNull { it as? Record }
    override fun recordLongClicks(): Observable<Record> = adapter.itemLongClicks.mapNotNull { it as? Record }
    override fun editRecordClicks(): Observable<String> = editRecordClicks
    override fun deleteRecordClicks(): Observable<String> = deleteRecordClicks
    override fun createProfitClicks(): Observable<Any> = menuHolder.menuItemClicks(R.id.new_profit)
    override fun chooseLongSumPeriodClicks(): Observable<Any> = menuHolder.menuItemClicks(R.id.long_sum)
    override fun chooseShortSumPeriodClicks(): Observable<Any> = menuHolder.menuItemClicks(R.id.short_sum)

    override fun showSpendsChanges(): Observable<Boolean> = menuHolder.menuItemCheckedChanges(R.id.show_spends)
    override fun showProfitsChanges(): Observable<Boolean> = menuHolder.menuItemCheckedChanges(R.id.show_profits)
    override fun showSumsChanges(): Observable<Boolean> = menuHolder.menuItemCheckedChanges(R.id.show_sums)
    override fun showChangeKindsChanges(): Observable<Boolean> = menuHolder.menuItemCheckedChanges(R.id.show_change_kinds)
    override fun showTimesChanges(): Observable<Boolean> = menuHolder.menuItemCheckedChanges(R.id.show_times)

    override fun sortByValueChanges(): Observable<Boolean> = menuHolder.menuItemCheckedChanges(R.id.sort_by_value)

    override fun longSumPeriodSelected(): Observable<Days> = longSumPeriodSelected
    override fun shortSumPeriodSelected(): Observable<Minutes> = shortSumPeriodSelected

    override fun addStubRecordsClicks(): Observable<Any> = menuHolder.menuItemClicks(R.id.add_stub_records)
    override fun clearAllClicks(): Observable<Any> = menuHolder.menuItemClicks(R.id.clear_all)

    override fun cancelSelection(): Observable<Any> = Observable.merge(
            RxView.clicks(closeSelectPanel_ImageView),
            cancelSelection
    )

    override fun deleteSelectedClicks(): Observable<Any> = selectModeMenuHolder.menuItemClicks(R.id.delete)
    override fun combineSelectedClicks(): Observable<Any> = selectModeMenuHolder.menuItemClicks(R.id.combine)
    override fun changeSelectedClicks(): Observable<Any> = selectModeMenuHolder.menuItemClicks(R.id.change)

    override fun render(vs: RecordsListViewState) {
        LogUtils.withErrorLoggingOnly { super.render(vs) }

        renderIfChanged({ showInfo.showChangeKinds }) { adapter.showChangeKinds = it }
        renderIfChanged({ showInfo.showTimes }) { adapter.showTimesInRecords = it }
        renderIfChanged({ !showInfo.showSums || sortByValue }) { adapter.showDatesInRecords = it }
        renderIfChanged({ selectedRecordsUuids }) { adapter.selectedRecordsUuids = it }
        renderIfChanged({ selectMode }) { adapter.selectMode = it }

        renderIfChangedWithFirstRendering({ records }) { records, firstRender ->
            if (records == null) return@renderIfChangedWithFirstRendering
            adapter.recordsChanges = vs.recordsChanges
            adapter.list = records
            if (key == RecordsListKey.Now && !layoutAnimationShown) {
                layoutAnimationShown = true
                records_RecyclerView.layoutAnimation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation_fall_down)
            }
            if (firstRender) {
                adapter.notifyDataSetChanged()
            } else {
                vs.diff.dispatchToAdapter(adapter)
            }
            if (layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
                records_RecyclerView.scrollToPosition(0)
            }
            val pendingCreatedRecordUuid = itemAnimator.pendingCreatedRecordUuid
            if (pendingCreatedRecordUuid != null) {
                val position = records.indexOfFirst { it is Record && it.uuid == pendingCreatedRecordUuid }
                if (position >= 0) {
                    records_RecyclerView.scrollToPosition(position)
                }
            }
            if (!initialScrollDone) {
                initialScrollDone = true
                val key = key
                LogUtils.d("RecordsListFragment key=$key")
                @Suppress("IMPLICIT_CAST_TO_ANY")
                when (key) {
                    RecordsListKey.Now -> Unit
                    is RecordsListKey.Date -> {
                        records
                                .indexOfFirst { it.date() <= key.date }
                                .let { maxOf(it - 5, 0) }
                                .also {
                                    LogUtils.d("RecordsListFragment records_RecyclerView.scrollToPosition($it)")
                                    records_RecyclerView.scrollToPosition(it)
                                }
                    }
                }.also {}
            }
        }

        renderIfChanged({ showInfo.newSpendVisible() }) {
            createSpendViewImpl.setVisible(it)
            if (!it) (requireActivity() as KeyboardManager).hideKeyboard()
        }

        menuHolder.menu?.also { menu ->
            renderIfChanged({ showInfo }) {
                menu.findItem(R.id.new_profit).isEnabled = it.newProfitEnable()

                menu.findItem(R.id.show_spends).isChecked = it.showSpends
                menu.findItem(R.id.show_profits).isChecked = it.showProfits
                menu.findItem(R.id.show_sums).isChecked = it.showSums
                menu.findItem(R.id.show_change_kinds).isChecked = it.showChangeKinds
                menu.findItem(R.id.show_times).isChecked = it.showTimes

                menu.findItem(R.id.show_spends).isEnabled = it.showSpendsEnable()
                menu.findItem(R.id.show_profits).isEnabled = it.showProfitsEnable()
            }

            renderIfChanged({ sortByValue }) {
                menu.findItem(R.id.sort_by_value).isChecked = it
            }

            renderIfChanged({ longSumPeriod }) {
                menu.findItem(R.id.long_sum).title = resources.getString(
                        R.string.menu_item_long_sum_format,
                        ChooseLongSumPeriodDialog.variantToString(it, resources)
                )
            }
            renderIfChanged({ shortSumPeriod }) {
                menu.findItem(R.id.short_sum).title = resources.getString(
                        R.string.menu_item_short_sum_format,
                        ChooseShortSumPeriodDialog.variantToString(it, resources)
                )
            }
        }

        renderIfChangedThree({ Triple(sumsInfo, longSumPeriod, shortSumPeriod) }) { (sumsInfo, longSumPeriodDays, shortSumPeriodMinutes) ->
            val longSumText = sumsInfo.longSum
                    ?.let {
                        resources.getString(
                                R.string.text_period_sum_format,
                                resources.formatTimeLetters(longSumPeriodDays),
                                it.toPointedString()
                        )
                    }
            val shortSumText = sumsInfo.shortSum
                    ?.let {
                        resources.getString(
                                R.string.text_period_sum_format,
                                resources.formatTimeLetters(shortSumPeriodMinutes),
                                it.toPointedString()
                        )
                    }
            val changesCountText = sumsInfo.changesCount
                    .takeIf { E.env.syncWithServer }
                    ?.let {
                        when (it) {
                            0 -> getString(R.string.no_changes_text)
                            else -> getString(R.string.changes_count_format, it)
                        }
                    }
            toolbar.subtitle = listOfNotNull(longSumText, shortSumText, changesCountText)
                    .reduceEmptyToNull { acc, s -> "$acc    $s" }
        }

        renderIfChanged({ syncState }) {
            toolbar.title = getString(R.string.fragment_title_records) + it.indicator
        }

        renderIfChangedWithFirstRendering({ selectMode }) { visible, firstRendering -> setDeletePanelVisible(visible, firstRendering) }
        renderIfChanged({ selectedRecordsUuids.size }) { selectedCount_VectorIntegerView.setInteger(it.toLong(), true) }
        renderIfChanged({ selectedSum }) {
            selectedSum_VectorIntegerView.digitColor = resources.color(
                    if (it >= 0) {
                        R.color.balance_positive
                    } else {
                        R.color.balance_negative
                    }
            )
            selectedSum_VectorIntegerView.setInteger(it, true)
        }

        selectModeMenuHolder.menu?.also { menu ->
            renderIfChanged({ canDeleteSelected }) { menu.findItem(R.id.delete).isEnabled = it }
            renderIfChanged({ canChangeSelected }) { menu.findItem(R.id.change).isEnabled = it }
            renderIfChanged({ canCombineSelected }) { menu.findItem(R.id.combine).isEnabled = it }
        }
    }

    override fun executeAction(va: ViewAction) {
        if (va !is RecordsListViewAction) null!!
        when (va) {
            is RecordsListViewAction.AskForRecordActions -> RecordActionsDialogFragmentBuilder
                    .newRecordActionsDialogFragment(va.recordUuid)
                    .makeShow(REQUEST_ASK_FOR_RECORD_ACTIONS)
            is RecordsListViewAction.AskToCreateRecord -> SaveRecordDialogFragmentBuilder
                    .newSaveRecordDialogFragment(SaveRecordKey.NewRecord(va.recordTypeId))
                    .makeShow()
            is RecordsListViewAction.AskToEditRecord -> SaveRecordDialogFragmentBuilder
                    .newSaveRecordDialogFragment(SaveRecordKey.EditRecord(va.recordUuid))
                    .makeShow()
            is RecordsListViewAction.AskToDeleteRecord -> DeleteRecordDialogFragmentBuilder
                    .newDeleteRecordDialogFragment(va.recordUuid)
                    .makeShow()
            is RecordsListViewAction.AskToChooseLongSumPeriod -> ChooseLongSumPeriodDialogBuilder
                    .newChooseLongSumPeriodDialog(va.days)
                    .makeShow(REQUEST_CHOOSE_LONG_SUM_PERIOD)
            is RecordsListViewAction.AskToChooseShortSumPeriod -> ChooseShortSumPeriodDialogBuilder
                    .newChooseShortSumPeriodDialog(va.minutes)
                    .makeShow(REQUEST_CHOOSE_SHORT_SUM_PERIOD)
            is RecordsListViewAction.OnRecordCreatedLocally -> itemAnimator.pendingCreatedRecordUuid = va.uuid
            is RecordsListViewAction.OnRecordEditedLocally -> Unit
            RecordsListViewAction.RerenderAll -> renderAll()
            is RecordsListViewAction.AskToCombineRecords -> CombineRecordsDialogFragmentBuilder
                    .newCombineRecordsDialogFragment(CombineRecordsDialogFragment.Key(va.recordUuids, va.categoryUuid, va.kind))
                    .makeShow()
            is RecordsListViewAction.AskToDeleteRecords -> DeleteRecordsListDialogFragmentBuilder
                    .newDeleteRecordsListDialogFragment(DeleteRecordsListDialogFragment.Key(va.recordUuids))
                    .makeShow()
            is RecordsListViewAction.AskToChangeRecords -> ChangeRecordsDialogFragmentBuilder
                    .newChangeRecordsDialogFragment(ChangeRecordsDialogFragment.Key(va.recordUuids))
                    .makeShow()
            RecordsListViewAction.ScrollToTop -> records_RecyclerView.scrollToPosition(0)
        }.also { }
    }

    override fun onBackPressed(): Boolean {
        if (currentViewState.selectMode) {
            cancelSelection.onNext(Any())
            return true
        }
        return false
    }

    private fun DialogFragment.makeShow(requestCode: Int? = null) = this
            .also { if (requestCode != null) it.setTargetFragment(this@RecordsListFragment, requestCode) }
            .show(this@RecordsListFragment.fragmentManager, null)
            .also { (this@RecordsListFragment.context as KeyboardManager).hideKeyboard() }

    private fun setDeletePanelVisible(visible: Boolean, firstRendering: Boolean) {
        val isRoot = requireFragmentManager().backStackEntryCount == 0
        val defaultIconState = if (isRoot) R.attr.state_drawer else R.attr.state_back_arrow
        val newState = intArrayOf(
                R.attr.state_close.let { if (visible) it else -it },
                defaultIconState.let { if (!visible) it else -it }
        )

        if (firstRendering) {
            toolbar.alpha = if (visible) 0f else 1f
            selectPanel_LinearLayout.alpha = if (visible) 1f else 0f
            selectPanel_LinearLayout.setVisible(visible)
            appBarLayout.setBackgroundColor(resources.color(if (visible) R.color.colorAccent else R.color.colorPrimary))
            closeSelectPanel_ImageView.setImageState(newState, true)
            closeSelectPanel_ImageView.jumpDrawablesToCurrentState()
            return
        }

        val animationDuration = resources.getInteger(R.integer.toolbar_icon_animation_duration).toLong()
        if (visible) {
            toolbar.animate()
                    .setDuration(animationDuration)
                    .alpha(0f)
            selectPanel_LinearLayout.setVisible(true)
            selectPanel_LinearLayout.animate()
                    .setDuration(animationDuration)
                    .alpha(1f)
            appBarLayout.animateBackgroundColor(
                    appBarLayout.getBackgroundColor() ?: resources.color(R.color.colorPrimary),
                    resources.color(R.color.colorAccent),
                    animationDuration
            )
            closeSelectPanel_ImageView.postDelayed({
                closeSelectPanel_ImageView?.setImageState(newState, true)
            }, 100)
        } else {
            closeSelectPanel_ImageView.post {
                closeSelectPanel_ImageView?.setImageState(newState, true)
            }
            closeSelectPanel_ImageView.postDelayed({
                toolbar?.animate()
                        ?.setDuration(animationDuration)
                        ?.alpha(1f)
                selectPanel_LinearLayout?.animate()
                        ?.setDuration(animationDuration)
                        ?.alpha(0f)
                        ?.withEndAction { selectPanel_LinearLayout?.setVisible(false) }
                appBarLayout?.animateBackgroundColor(
                        appBarLayout?.getBackgroundColor() ?: resources.color(R.color.colorAccent),
                        resources.color(R.color.colorPrimary),
                        animationDuration
                )
            }, animationDuration)
        }
    }
}