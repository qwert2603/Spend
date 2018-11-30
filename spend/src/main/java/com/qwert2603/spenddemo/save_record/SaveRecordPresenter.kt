package com.qwert2603.spenddemo.save_record

import com.qwert2603.andrlib.base.mvi.BasePresenter
import com.qwert2603.andrlib.base.mvi.PartialChange
import com.qwert2603.andrlib.schedulers.UiSchedulerProvider
import com.qwert2603.spenddemo.model.entity.Record
import com.qwert2603.spenddemo.model.entity.RecordDraft
import com.qwert2603.spenddemo.model.entity.toRecordDraft
import com.qwert2603.spenddemo.utils.*
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SaveRecordPresenter @Inject constructor(
        saveRecordKey: SaveRecordKey,
        private val saveRecordInteractor: SaveRecordInteractor,
        uiSchedulerProvider: UiSchedulerProvider
) : BasePresenter<SaveRecordView, SaveRecordViewState>(uiSchedulerProvider) {

    override val initialState = SaveRecordViewState(
            isNewRecord = saveRecordKey is SaveRecordKey.NewRecord,
            recordDraft = when (saveRecordKey) {
                is SaveRecordKey.EditRecord -> SaveRecordViewState.DRAFT_IS_LOADING
                is SaveRecordKey.NewRecord -> {
                    saveRecordInteractor.getDraft(saveRecordKey.recordTypeId)
                            ?: RecordDraft.new(saveRecordKey.recordTypeId)
                }
            },
            serverCategory = null,
            serverKind = null,
            serverDate = null,
            serverTime = null,
            serverValue = null,
            justChangedOnServer = false,
            existingRecord = null
    )

    private val serverRecordChanges: Observable<Wrapper<Record>> =
            when (saveRecordKey) {
                is SaveRecordKey.EditRecord -> saveRecordInteractor.getRecordChanges(saveRecordKey.uuid)
                is SaveRecordKey.NewRecord -> Observable.never()
            }
                    .shareAfterViewSubscribed()

    private val clearDraft = PublishSubject.create<Any>()

    private val onDateSelectedIntent = intent { it.onDateSelected() }.shareAfterViewSubscribed()
    private val onTimeSelectedIntent = intent { it.onTimeSelected() }.shareAfterViewSubscribed()
    private val categoryNameChangesIntent = intent { it.categoryNameChanges() }.shareAfterViewSubscribed()
    private val kindChangesIntent = intent { it.kindChanges() }.shareAfterViewSubscribed()

    private val onCategoryUuidSelectedIntent = intent { it.onCategoryUuidSelected() }.shareAfterViewSubscribed()
    private val onCategoryUuidAndKindSelectedIntent = intent { it.onCategoryUuidAndKindSelected() }.shareAfterViewSubscribed()

    override val partialChanges: Observable<PartialChange> = Observable.merge(listOf(
            serverRecordChanges
                    .take(1)
                    .flatMap {
                        if (it.t != null) {
                            Observable.just(SaveRecordPartialChange.EditingRecordLoaded(it.t.toRecordDraft()))
                        } else {
                            viewActions.onNext(SaveRecordViewAction.EditingRecordNotFound)
                            Observable.empty()
                        }
                    },
            serverRecordChanges
                    .mapNotNull { it.t }
                    .shareReplayLast()
                    .let { recordChanges ->
                        fun <T> serverPartialChanges(
                                mapper: (Record) -> T,
                                partialChangesCreator: (T) -> PartialChange
                        ): Observable<PartialChange> = recordChanges
                                .map(mapper)
                                .distinctUntilChanged()
                                .skip(1)
                                .map(partialChangesCreator)

                        Observable.merge(
                                serverPartialChanges({ it.kind }, { SaveRecordPartialChange.KindChangeOnServer(it) }),
                                serverPartialChanges({ it.value }, { SaveRecordPartialChange.ValueChangeOnServer(it) }),
                                serverPartialChanges({ it.date }, { SaveRecordPartialChange.DateChangeOnServer(it) }),
                                serverPartialChanges({ it.time.wrap() }, { SaveRecordPartialChange.TimeChangeOnServer(it.t) })
                        )
                    },
            serverRecordChanges
                    .mapNotNull { it.t }
                    .distinctUntilChanged()
                    .skip(1)
                    .switchMap {
                        Observable.interval(0, 1000, TimeUnit.MILLISECONDS)
                                .take(2)
                                .map { it == 0L }
                    }
                    .map { SaveRecordPartialChange.RecordJustChangedOnServer(it) },
            serverRecordChanges
                    .mapNotNull { it.t }
                    .map { SaveRecordPartialChange.ExistingRecordChanged(it.toRecordDraft()) },

            intent { it.onServerCategoryResolved() }
                    .map { SaveRecordPartialChange.CategoryServerResolved(it) },
            intent { it.onServerKindResolved() }
                    .map { SaveRecordPartialChange.KindServerResolved(it) },
            intent { it.onServerDateResolved() }
                    .map { SaveRecordPartialChange.DateServerResolved(it) },
            intent { it.onServerTimeResolved() }
                    .map { SaveRecordPartialChange.TimeServerResolved(it) },
            intent { it.onServerValueResolved() }
                    .map { SaveRecordPartialChange.ValueServerResolved(it) },

            categoryNameChangesIntent
                    .map { SaveRecordPartialChange.CategoryNameChanged(it) },
            viewStateObservable
                    .filter { it.recordDraft != SaveRecordViewState.DRAFT_IS_LOADING }
                    .map { it.recordDraft.recordCategoryName }
                    .distinctUntilChanged()
                    .withLatestFrom(viewStateObservable.map { it.recordDraft.recordTypeId }, makePair())
                    .switchMap { (categoryName, recordTypeId) ->
                        saveRecordInteractor
                                .getRecordCategory(recordTypeId, categoryName)
                                .map { SaveRecordPartialChange.CategoryUuidChanged(it.t?.recordCategory?.uuid) }
                    },
            onCategoryUuidSelectedIntent
                    .map { SaveRecordPartialChange.CategoryUuidChanged(it) },
            viewStateObservable
                    .filter { it.recordDraft != SaveRecordViewState.DRAFT_IS_LOADING }
                    .map { it.recordDraft.recordCategoryUuid.wrap() }
                    .distinctUntilChanged()
                    .switchMap { (categoryUuid) ->
                        if (categoryUuid != null) {
                            saveRecordInteractor.getRecordCategory(categoryUuid)
                                    .map { SaveRecordPartialChange.CategoryNameChanged(it.recordCategory.name) }
                        } else {
                            Observable.empty()
                        }
                    },
            kindChangesIntent
                    .map { SaveRecordPartialChange.KindChanged(it) },
            intent { it.valueChanges() }
                    .map { SaveRecordPartialChange.ValueChanged(it) },
            onDateSelectedIntent
                    .map { SaveRecordPartialChange.DateSelected(it.t) },
            onTimeSelectedIntent
                    .map { SaveRecordPartialChange.TimeSelected(it.t) },
            onCategoryUuidAndKindSelectedIntent
                    .withLatestFrom(viewStateObservable.map { it.recordDraft.recordTypeId }, makePair())
                    .switchMapSingle { (pair, recordTypeId) ->
                        val (categoryUuid, kind) = pair
                        saveRecordInteractor.getLastValueOfKind(recordTypeId, categoryUuid, kind)
                                .map { SaveRecordPartialChange.KindSelected(categoryUuid, kind, it) }
                    },
            clearDraft
                    .map { SaveRecordPartialChange.DraftCleared }
    ))

    override fun stateReducer(vs: SaveRecordViewState, change: PartialChange): SaveRecordViewState {
        if (change !is SaveRecordPartialChange) null!!
        return when (change) {
            is SaveRecordPartialChange.EditingRecordLoaded -> if (vs.isNewRecord) {
                vs
            } else {
                vs.copy(recordDraft = change.recordDraft)
            }
            is SaveRecordPartialChange.CategoryUuidChanged -> vs.copy(recordDraft = vs.recordDraft.copy(recordCategoryUuid = change.categoryUuid))
            is SaveRecordPartialChange.CategoryNameChanged -> vs.copy(recordDraft = vs.recordDraft.copy(recordCategoryName = change.categoryName))
            is SaveRecordPartialChange.KindChanged -> vs.copy(recordDraft = vs.recordDraft.copy(kind = change.kind))
            is SaveRecordPartialChange.ValueChanged -> vs.copy(recordDraft = vs.recordDraft.copy(value = change.value))
            is SaveRecordPartialChange.KindSelected -> vs.copy(recordDraft = vs.recordDraft.copy(
                    recordCategoryUuid = change.categoryUuid,
                    kind = change.kind,
                    value = change.lastValue
            ))
            is SaveRecordPartialChange.DateSelected -> {
                val (nowDate, nowTime) = DateUtils.getNow()
                if (vs.recordDraft.date == null && change.date == nowDate) {
                    vs.copy(recordDraft = vs.recordDraft.copy(
                            date = change.date,
                            time = nowTime
                    ))
                } else {
                    vs.copy(recordDraft = vs.recordDraft.copy(
                            date = change.date,
                            time = if (change.date != null) vs.recordDraft.time else null
                    ))
                }
            }
            is SaveRecordPartialChange.TimeSelected -> vs.copy(recordDraft = vs.recordDraft.copy(time = change.time.takeIf { vs.recordDraft.date != null }))
            SaveRecordPartialChange.DraftCleared -> vs.copy(recordDraft = RecordDraft.new(vs.recordDraft.recordTypeId))
            is SaveRecordPartialChange.CategoryChangeOnServer -> vs.copy(serverCategory = change.category)
            is SaveRecordPartialChange.KindChangeOnServer -> vs.copy(serverKind = change.kind)
            is SaveRecordPartialChange.ValueChangeOnServer -> vs.copy(serverValue = change.value)
            is SaveRecordPartialChange.DateChangeOnServer -> vs.copy(serverDate = change.date)
            is SaveRecordPartialChange.TimeChangeOnServer -> vs.copy(serverTime = change.time.wrap())
            is SaveRecordPartialChange.RecordJustChangedOnServer -> vs.copy(justChangedOnServer = change.justChanged)
            is SaveRecordPartialChange.ExistingRecordChanged -> vs.copy(existingRecord = change.existingRecord)
            is SaveRecordPartialChange.CategoryServerResolved -> vs.copy(
                    recordDraft = if (change.acceptFromServer && vs.serverCategory != null) {
                        vs.recordDraft.copy(
                                recordCategoryUuid = vs.serverCategory.uuid,
                                recordCategoryName = vs.serverCategory.name
                        )
                    } else {
                        vs.recordDraft
                    },
                    serverKind = null
            )
            is SaveRecordPartialChange.KindServerResolved -> vs.copy(
                    recordDraft = if (change.acceptFromServer && vs.serverKind != null) vs.recordDraft.copy(kind = vs.serverKind) else vs.recordDraft,
                    serverKind = null
            )
            is SaveRecordPartialChange.ValueServerResolved -> vs.copy(
                    recordDraft = if (change.acceptFromServer && vs.serverValue != null) vs.recordDraft.copy(value = vs.serverValue) else vs.recordDraft,
                    serverValue = null
            )
            is SaveRecordPartialChange.DateServerResolved -> vs.copy(
                    recordDraft = if (change.acceptFromServer && vs.serverDate != null) vs.recordDraft.copy(date = vs.serverDate) else vs.recordDraft,
                    serverDate = null
            )
            is SaveRecordPartialChange.TimeServerResolved -> vs.copy(
                    recordDraft = if (change.acceptFromServer && vs.serverTime != null) vs.recordDraft.copy(time = vs.serverTime.t) else vs.recordDraft,
                    serverTime = null
            )
        }
    }

    override fun bindIntents() {
        viewStateObservable
                .skip(1) // skip initial value.
                .map { it.recordDraft }
                .filter { it.isNewRecord }
                .doOnNext { saveRecordInteractor.saveDraft(it.recordTypeId, it) }
                .subscribeToView()

        Observable
                .merge(
                        intent { it.onCategoryInputClicked() }
                                .withLatestFrom(viewStateObservable, secondOfTwo())
                                .map { it.recordDraft.recordCategoryName },
                        categoryNameChangesIntent
                                .debounce(100, TimeUnit.MILLISECONDS)
                )
                .withLatestFrom(viewStateObservable.map { it.recordDraft.recordTypeId }, makePair())
                .switchMapSingle { (categoryName, recordTypeId) ->
                    saveRecordInteractor.getCategorySuggestions(recordTypeId, categoryName)
                            .doOnSuccess { categories ->
                                if (categoryName !in categories.map { it.recordCategory.name }) {
                                    viewActions.onNext(SaveRecordViewAction.ShowCategorySuggestions(categories, categoryName))
                                } else {
                                    viewActions.onNext(SaveRecordViewAction.HideCategorySuggestions)
                                }
                            }
                }
                .subscribeToView()

        Observable
                .merge(
                        intent { it.onKindInputClicked() }
                                .withLatestFrom(viewStateObservable, secondOfTwo())
                                .map { it.recordDraft.kind },
                        kindChangesIntent
                                .debounce(100, TimeUnit.MILLISECONDS)
                )
                .withLatestFrom(viewStateObservable.map { it.recordDraft }, makePair())
                .switchMapSingle { (kind, recordDraft) ->
                    saveRecordInteractor.getKindSuggestions(recordDraft.recordTypeId, recordDraft.recordCategoryUuid, kind)
                            .doOnSuccess { recordKinds ->
                                if (kind !in recordKinds.map { it.kind }) {
                                    viewActions.onNext(SaveRecordViewAction.ShowKindSuggestions(
                                            suggestions = recordKinds,
                                            search = kind,
                                            withCategory = recordDraft.recordCategoryUuid == null
                                    ))
                                } else {
                                    viewActions.onNext(SaveRecordViewAction.HideKindSuggestions)
                                }
                            }
                }
                .subscribeToView()

        intent { it.selectCategoryClicks() }
                .withLatestFrom(viewStateObservable, secondOfTwo())
                .doOnNext { viewActions.onNext(SaveRecordViewAction.AskToSelectCategory(it.recordDraft.recordTypeId)) }
                .subscribeToView()

        intent { it.selectKindClicks() }
                .withLatestFrom(viewStateObservable, secondOfTwo())
                .doOnNext { viewActions.onNext(SaveRecordViewAction.AskToSelectKind(it.recordDraft.recordTypeId, it.recordDraft.recordCategoryUuid)) }
                .subscribeToView()

        intent { it.selectDateClicks() }
                .withLatestFrom(viewStateObservable, secondOfTwo())
                .doOnNext {
                    viewActions.onNext(SaveRecordViewAction.AskToSelectDate(
                            it.recordDraft.date ?: DateUtils.getNow().first
                    ))
                }
                .subscribeToView()

        intent { it.selectTimeClicks() }
                .withLatestFrom(viewStateObservable, secondOfTwo())
                .doOnNext {
                    viewActions.onNext(SaveRecordViewAction.AskToSelectTime(
                            it.recordDraft.time ?: DateUtils.getNow().second
                    ))
                }
                .subscribeToView()

        Observable.merge(onDateSelectedIntent, onTimeSelectedIntent)
                .doOnNext { viewActions.onNext(SaveRecordViewAction.FocusOnCategoryInput) }
                .subscribeToView()

        onCategoryUuidSelectedIntent
                .doOnNext { viewActions.onNext(SaveRecordViewAction.FocusOnKindInput) }
                .subscribeToView()

        onCategoryUuidAndKindSelectedIntent
                .doOnNext { viewActions.onNext(SaveRecordViewAction.FocusOnValueInput) }
                .subscribeToView()

        serverRecordChanges
                .buffer(2, 1)
                .mapNotNull { (prev, current) ->
                    if (prev.t != null && current.t == null) {
                        prev.t.recordCategory.recordTypeId
                    } else {
                        null
                    }
                }
                .take(1)
                .doOnNext { viewActions.onNext(SaveRecordViewAction.EditingRecordDeletedOnServer(it)) }
                .subscribeToView()

        intent { it.saveClicks() }
                .withLatestFrom(viewStateObservable, secondOfTwo())
                .mapNotNull { it.recordDraft }
                .filter { it.isValid() }
                .doOnNext {
                    saveRecordInteractor.saveRecord(it)
                    viewActions.onNext(SaveRecordViewAction.Close) // this is for dialog
                    clearDraft.onNext(Any())
                    viewActions.onNext(SaveRecordViewAction.FocusOnCategoryInput) // this is for CreateSpendViewImpl
                }
                .subscribeToView()

        RxUtils.dateChanges()
                .doOnNext { viewActions.onNext(SaveRecordViewAction.RerenderAll) }
                .subscribeToView()

        super.bindIntents()
    }
}