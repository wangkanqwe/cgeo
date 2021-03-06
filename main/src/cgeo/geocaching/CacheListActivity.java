package cgeo.geocaching;

import cgeo.geocaching.activity.AbstractActivity;
import cgeo.geocaching.activity.AbstractListActivity;
import cgeo.geocaching.activity.FilteredActivity;
import cgeo.geocaching.activity.Progress;
import cgeo.geocaching.apps.cache.navi.NavigationAppFactory;
import cgeo.geocaching.apps.cachelist.CacheListAppFactory;
import cgeo.geocaching.connector.gc.SearchHandler;
import cgeo.geocaching.enumerations.CacheListType;
import cgeo.geocaching.enumerations.CacheType;
import cgeo.geocaching.enumerations.LoadFlags;
import cgeo.geocaching.enumerations.StatusCode;
import cgeo.geocaching.export.ExportFactory;
import cgeo.geocaching.files.GPXImporter;
import cgeo.geocaching.filter.FilterUserInterface;
import cgeo.geocaching.filter.IFilter;
import cgeo.geocaching.geopoint.Geopoint;
import cgeo.geocaching.list.StoredList;
import cgeo.geocaching.loaders.AbstractSearchLoader;
import cgeo.geocaching.loaders.AbstractSearchLoader.CacheListLoaderType;
import cgeo.geocaching.loaders.AddressGeocacheListLoader;
import cgeo.geocaching.loaders.CoordsGeocacheListLoader;
import cgeo.geocaching.loaders.HistoryGeocacheListLoader;
import cgeo.geocaching.loaders.KeywordGeocacheListLoader;
import cgeo.geocaching.loaders.NextPageGeocacheListLoader;
import cgeo.geocaching.loaders.OfflineGeocacheListLoader;
import cgeo.geocaching.loaders.OwnerGeocacheListLoader;
import cgeo.geocaching.loaders.RemoveFromHistoryLoader;
import cgeo.geocaching.loaders.UsernameGeocacheListLoader;
import cgeo.geocaching.maps.CGeoMap;
import cgeo.geocaching.network.Cookies;
import cgeo.geocaching.network.Network;
import cgeo.geocaching.network.Parameters;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.sorting.CacheComparator;
import cgeo.geocaching.sorting.ComparatorUserInterface;
import cgeo.geocaching.ui.CacheListAdapter;
import cgeo.geocaching.ui.LoggingUI;
import cgeo.geocaching.ui.WeakReferenceHandler;
import cgeo.geocaching.utils.AsyncTaskWithProgress;
import cgeo.geocaching.utils.DateUtils;
import cgeo.geocaching.utils.GeoDirHandler;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.RunnableWithArgument;

import ch.boye.httpclientandroidlib.HttpResponse;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNull;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CacheListActivity extends AbstractListActivity implements FilteredActivity, LoaderManager.LoaderCallbacks<SearchResult> {

    private static final int MAX_LIST_ITEMS = 1000;

    private static final int MSG_DONE = -1;
    private static final int MSG_RESTART_GEO_AND_DIR = -2;
    private static final int MSG_CANCEL = -99;

    private CacheListType type = null;
    private Geopoint coords = null;
    private SearchResult search = null;
    /** The list of shown caches shared with Adapter. Don't manipulate outside of main thread only with Handler */
    private final List<Geocache> cacheList = new ArrayList<Geocache>();
    private CacheListAdapter adapter = null;
    private LayoutInflater inflater = null;
    private View listFooter = null;
    private TextView listFooterText = null;
    private final Progress progress = new Progress();
    private String title = "";
    private int detailTotal = 0;
    private int detailProgress = 0;
    private long detailProgressTime = 0L;
    private LoadDetailsThread threadDetails = null;
    private LoadFromWebThread threadWeb = null;
    private int listId = StoredList.TEMPORARY_LIST_ID; // Only meaningful for the OFFLINE type
    private final GeoDirHandler geoDirHandler = new GeoDirHandler() {

        @Override
        public void updateGeoData(final IGeoData geo) {
            if (geo.getCoords() != null) {
                adapter.setActualCoordinates(geo.getCoords());
            }
            if (!Settings.isUseCompass() || geo.getSpeed() > 5) { // use GPS when speed is higher than 18 km/h
                adapter.setActualHeading(geo.getBearing());
            }
        }

        @Override
        public void updateDirection(final float direction) {
            if (!Settings.isLiveList()) {
                return;
            }

            if (app.currentGeo().getSpeed() <= 5) { // use compass when speed is lower than 18 km/h) {
                final float northHeading = DirectionProvider.getDirectionNow(CacheListActivity.this, direction);
                adapter.setActualHeading(northHeading);
            }
        }

    };
    private ContextMenuInfo lastMenuInfo;
    private String contextMenuGeocode = "";

    // FIXME: This method has mostly been replaced by the loaders. But it still contains a license agreement check.
    public void handleCachesLoaded() {
        try {
            setAdapter();

            updateTitle();

            showFooterMoreCaches();

            if (search != null && search.getError() == StatusCode.UNAPPROVED_LICENSE) {
                final AlertDialog.Builder dialog = new AlertDialog.Builder(this);
                dialog.setTitle(res.getString(R.string.license));
                dialog.setMessage(res.getString(R.string.err_license));
                dialog.setCancelable(true);
                dialog.setNegativeButton(res.getString(R.string.license_dismiss), new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        Cookies.clearCookies();
                        dialog.cancel();
                    }
                });
                dialog.setPositiveButton(res.getString(R.string.license_show), new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        Cookies.clearCookies();
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.geocaching.com/software/agreement.aspx?ID=0")));
                    }
                });

                final AlertDialog alert = dialog.create();
                alert.show();
            } else if (search != null && search.getError() != null) {
                showToast(res.getString(R.string.err_download_fail) + ' ' + search.getError().getErrorString(res) + '.');

                hideLoading();
                showProgress(false);

                finish();
                return;
            }

            setAdapterCurrentCoordinates(false);
        } catch (final Exception e) {
            showToast(res.getString(R.string.err_detail_cache_find_any));
            Log.e("CacheListActivity.loadCachesHandler", e);

            hideLoading();
            showProgress(false);

            finish();
            return;
        }

        try {
            hideLoading();
            showProgress(false);
        } catch (final Exception e2) {
            Log.e("CacheListActivity.loadCachesHandler.2", e2);
        }

        adapter.setSelectMode(false);
    }

    private final Handler loadCachesHandler = new LoadCachesHandler(this);

    private static class LoadCachesHandler extends WeakReferenceHandler<CacheListActivity> {

        protected LoadCachesHandler(CacheListActivity activity) {
            super(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            final CacheListActivity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.handleCachesLoaded();
        }
    }

    /**
     * Loads the caches and fills the {@link #cacheList} according to {@link #search} content.
     *
     * If {@link #search} is <code>null</code>, this does nothing.
     */

    private void replaceCacheListFromSearch() {
        if (search != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cacheList.clear();

                    // The database search was moved into the UI call intentionally. If this is done before the runOnUIThread,
                    // then we have 2 sets of caches in memory. This can lead to OOM for huge cache lists.
                    final Set<Geocache> cachesFromSearchResult = search.getCachesFromSearchResult(LoadFlags.LOAD_CACHE_OR_DB);

                    cacheList.addAll(cachesFromSearchResult);
                    adapter.reFilter();
                    updateTitle();
                    showFooterMoreCaches();
                }
            });
        }
    }

    protected void updateTitle() {
        final ArrayList<Integer> numbers = new ArrayList<Integer>();
        if (adapter.isFiltered()) {
            numbers.add(adapter.getCount());
        }
        if (search != null) {
            numbers.add(search.getCount());
        }
        if (numbers.isEmpty()) {
            setTitle(title);
        }
        else {
            setTitle(title + " [" + StringUtils.join(numbers, '/') + ']');
        }
    }

    private final Handler loadDetailsHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            setAdapter();

            if (msg.what > -1) {
                cacheList.get(msg.what).setStatusChecked(false);

                adapter.notifyDataSetChanged();

                final int secondsElapsed = (int) ((System.currentTimeMillis() - detailProgressTime) / 1000);
                final int minutesRemaining = ((detailTotal - detailProgress) * secondsElapsed / ((detailProgress > 0) ? detailProgress : 1) / 60);

                progress.setProgress(detailProgress);
                if (minutesRemaining < 1) {
                    progress.setMessage(res.getString(R.string.caches_downloading) + " " + res.getString(R.string.caches_eta_ltm));
                } else {
                    progress.setMessage(res.getString(R.string.caches_downloading) + " " + minutesRemaining + " " + res.getQuantityString(R.plurals.caches_eta_mins, minutesRemaining));
                }
            } else if (msg.what == MSG_CANCEL) {
                if (threadDetails != null) {
                    threadDetails.kill();
                }
            } else if (msg.what == MSG_RESTART_GEO_AND_DIR) {
                startGeoAndDir();
            } else {
                if (search != null) {
                    final Set<Geocache> cacheListTmp = search.getCachesFromSearchResult(LoadFlags.LOAD_CACHE_OR_DB);
                    if (CollectionUtils.isNotEmpty(cacheListTmp)) {
                        cacheList.clear();
                        cacheList.addAll(cacheListTmp);
                    }
                }

                setAdapterCurrentCoordinates(false);

                showProgress(false);
                progress.dismiss();

                startGeoAndDir();
            }
        }
    };

    /**
     * TODO Possibly parts should be a Thread not a Handler
     */
    private final Handler downloadFromWebHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            setAdapter();

            adapter.notifyDataSetChanged();

            if (msg.what == 0) { //no caches
                progress.setMessage(res.getString(R.string.web_import_waiting));
            } else if (msg.what == 1) { //cache downloading
                progress.setMessage(res.getString(R.string.web_downloading) + " " + msg.obj + '…');
            } else if (msg.what == 2) { //Cache downloaded
                progress.setMessage(res.getString(R.string.web_downloaded) + " " + msg.obj + '…');
                refreshCurrentList();
            } else if (msg.what == -2) {
                progress.dismiss();
                showToast(res.getString(R.string.sendToCgeo_download_fail));
                finish();
            } else if (msg.what == -3) {
                progress.dismiss();
                showToast(res.getString(R.string.sendToCgeo_no_registration));
                finish();
            } else if (msg.what == MSG_CANCEL) {
                if (threadWeb != null) {
                    threadWeb.kill();
                }
            } else {
                adapter.setSelectMode(false);

                replaceCacheListFromSearch();

                progress.dismiss();
            }
        }
    };
    private final Handler clearOfflineLogsHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_CANCEL) {
                adapter.setSelectMode(false);

                refreshCurrentList();

                replaceCacheListFromSearch();

                progress.dismiss();
            }
        }
    };

    private final Handler importGpxAttachementFinishedHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            refreshCurrentList();
        }
    };
    private AbstractSearchLoader currentLoader;
    private String newListName = StringUtils.EMPTY;

    public CacheListActivity() {
        super(true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTheme();
        setContentView(R.layout.cacheslist_activity);

        // get parameters
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            final Object typeObject = extras.get(Intents.EXTRA_LIST_TYPE);
            type = (typeObject instanceof CacheListType) ? (CacheListType) typeObject : CacheListType.OFFLINE;
            coords = extras.getParcelable(Intents.EXTRA_COORDS);
        }
        else {
            extras = new Bundle();
        }
        if (isInvokedFromAttachment()) {
            type = CacheListType.OFFLINE;
            if (coords == null) {
                coords = new Geopoint(0.0, 0.0);
            }
        }

        // Add the list selection in code. This way we can leave the XML layout of the action bar the same as for other activities.
        final View titleBar = findViewById(R.id.actionbar_title);
        titleBar.setClickable(true);
        titleBar.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                selectList();
            }
        });

        setTitle(title);
        setAdapter();

        prepareFilterBar();

        currentLoader = (AbstractSearchLoader) getSupportLoaderManager().initLoader(type.ordinal(), extras, this);

        // init
        if (CollectionUtils.isNotEmpty(cacheList)) {
            // currentLoader can be null if this activity is created from a map, as onCreateLoader() will return null.
            if (currentLoader != null && currentLoader.isStarted()) {
                showFooterLoadingCaches();
            } else {
                showFooterMoreCaches();
            }
        }

        if (isInvokedFromAttachment()) {
            importGpxAttachement();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (currentLoader != null && currentLoader.isLoading()) {
            showFooterLoadingCaches();
        }
    }

    private boolean isConcreteList() {
        return type == CacheListType.OFFLINE &&
                (listId == StoredList.STANDARD_LIST_ID || listId >= DataStore.customListIdOffset);
    }

    private boolean isInvokedFromAttachment() {
        return Intent.ACTION_VIEW.equals(getIntent().getAction());
    }

    private void importGpxAttachement() {
        new StoredList.UserInterface(this).promptForListSelection(R.string.gpx_import_select_list_title, new RunnableWithArgument<Integer>() {

            @Override
            public void run(Integer listId) {
                new GPXImporter(CacheListActivity.this, listId, importGpxAttachementFinishedHandler).importGPX();
                switchListById(listId);
            }
        }, true, 0);
    }

    @Override
    public void onResume() {
        super.onResume();

        startGeoAndDir();

        adapter.setSelectMode(false);
        setAdapterCurrentCoordinates(true);

        if (loadCachesHandler != null && search != null) {
            replaceCacheListFromSearch();
            loadCachesHandler.sendEmptyMessage(0);
        }

        // refresh standard list if it has changed (new caches downloaded)
        if (type == CacheListType.OFFLINE && listId >= StoredList.STANDARD_LIST_ID && search != null) {
            final SearchResult newSearch = DataStore.getBatchOfStoredCaches(coords, Settings.getCacheType(), listId);
            if (newSearch.getTotal() != search.getTotal()) {
                refreshCurrentList();
            }
        }
    }

    private void setAdapterCurrentCoordinates(final boolean forceSort) {
        final Geopoint coordsNow = app.currentGeo().getCoords();
        if (coordsNow != null) {
            adapter.setActualCoordinates(coordsNow);
            if (forceSort) {
                adapter.forceSort();
            }
        }
    }

    @Override
    public void onPause() {
        removeGeoAndDir();

        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.cache_list_options, menu);

        CacheListAppFactory.addMenuItems(menu, this, res);

        return true;
    }

    private static void setVisible(final Menu menu, final int itemId, final boolean visible) {
        menu.findItem(itemId).setVisible(visible);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final boolean isHistory = type == CacheListType.HISTORY;
        final boolean isOffline = type == CacheListType.OFFLINE;
        final boolean isEmpty = cacheList.isEmpty();
        final boolean isConcrete = isConcreteList();

        try {
            if (adapter.isSelectMode()) {
                menu.findItem(R.id.menu_switch_select_mode).setTitle(res.getString(R.string.caches_select_mode_exit))
                        .setIcon(R.drawable.ic_menu_clear_playlist);
            } else {
                menu.findItem(R.id.menu_switch_select_mode).setTitle(res.getString(R.string.caches_select_mode))
                        .setIcon(R.drawable.ic_menu_agenda);
            }
            menu.findItem(R.id.menu_invert_selection).setVisible(adapter.isSelectMode());


            setVisible(menu, R.id.menu_switch_select_mode, !isEmpty);
            setVisible(menu, R.id.submenu_manage, isOffline || isHistory);
            setVisible(menu, R.id.submenu_manage_lists, isOffline);

            setVisible(menu, R.id.menu_sort, !isEmpty && !isHistory);
            setVisible(menu, R.id.menu_refresh_stored, !isEmpty && (isConcrete || type != CacheListType.OFFLINE));
            setVisible(menu, R.id.menu_drop_caches, !isEmpty && isOffline);
            setVisible(menu, R.id.menu_drop_caches_and_list, isConcrete && !isEmpty && isOffline);
            setVisible(menu, R.id.menu_delete_events, isConcrete && !isEmpty && containsEvents());
            setVisible(menu, R.id.menu_move_to_list, isOffline && !isEmpty);
            setVisible(menu, R.id.menu_export, !isEmpty && (isHistory || isOffline));
            setVisible(menu, R.id.menu_remove_from_history, !isEmpty && isHistory);
            setVisible(menu, R.id.menu_clear_offline_logs, !isEmpty && containsOfflineLogs() && (isHistory || isOffline));
            setVisible(menu, R.id.menu_import_web, isOffline && Settings.getWebDeviceCode() != null);
            setVisible(menu, R.id.menu_import_gpx, isOffline);
            setVisible(menu, R.id.menu_refresh_stored_top, !isOffline);

            if (!isOffline && !isHistory) {
                menu.findItem(R.id.menu_refresh_stored_top).setTitle(R.string.caches_store_offline);
            }

            final boolean hasSelection = adapter != null && adapter.getCheckedCount() > 0;
            final boolean isNonDefaultList = isConcrete && listId != StoredList.STANDARD_LIST_ID;

            if (isOffline || type == CacheListType.HISTORY) { // only offline list
                setMenuItemLabel(menu, R.id.menu_drop_caches, R.string.caches_drop_selected, R.string.caches_drop_all);
                setMenuItemLabel(menu, R.id.menu_refresh_stored, R.string.caches_refresh_selected, R.string.caches_refresh_all);
                setMenuItemLabel(menu, R.id.menu_move_to_list, R.string.caches_move_selected, R.string.caches_move_all);
            } else { // search and global list (all other than offline and history)
                setMenuItemLabel(menu, R.id.menu_refresh_stored, R.string.caches_store_selected, R.string.caches_store_offline);
            }

            // make combined list deletion only possible when there are no filters, as that leads to confusion for the hidden caches
            menu.findItem(R.id.menu_drop_caches_and_list).setVisible(isOffline && !hasSelection && isNonDefaultList && !adapter.isFiltered() && Settings.getCacheType() == CacheType.ALL);

            menu.findItem(R.id.menu_drop_list).setVisible(isNonDefaultList);
            menu.findItem(R.id.menu_rename_list).setVisible(isNonDefaultList);

            final boolean multipleLists = DataStore.getLists().size() >= 2;
            menu.findItem(R.id.menu_switch_list).setVisible(multipleLists);
            menu.findItem(R.id.menu_move_to_list).setVisible(!isEmpty);

            setMenuItemLabel(menu, R.id.menu_remove_from_history, R.string.cache_remove_from_history, R.string.cache_clear_history);
            setMenuItemLabel(menu, R.id.menu_export, R.string.export, R.string.export);
        } catch (final RuntimeException e) {
            Log.e("CacheListActivity.onPrepareOptionsMenu", e);
        }

        return true;
    }

    private boolean containsEvents() {
        for (final Geocache cache : adapter.getCheckedOrAllCaches()) {
            if (cache.isEventCache()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOfflineLogs() {
        for (final Geocache cache : adapter.getCheckedOrAllCaches()) {
            if (cache.isLogOffline()) {
                return true;
            }
        }
        return false;
    }

    private void setMenuItemLabel(final Menu menu, final int menuId, final int resIdSelection, final int resId) {
        final MenuItem menuItem = menu.findItem(menuId);
        if (menuItem == null) {
            return;
        }
        final boolean hasSelection = adapter != null && adapter.getCheckedCount() > 0;
        if (hasSelection) {
            menuItem.setTitle(res.getString(resIdSelection) + " (" + adapter.getCheckedCount() + ")");
        } else {
            menuItem.setTitle(res.getString(resId));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_switch_select_mode:
                adapter.switchSelectMode();
                invalidateOptionsMenuCompatible();
                return true;
            case R.id.menu_refresh_stored_top:
            case R.id.menu_refresh_stored:
                refreshStored(adapter.getCheckedOrAllCaches());
                invalidateOptionsMenuCompatible();
                return true;
            case R.id.menu_drop_caches:
                dropStored(false);
                invalidateOptionsMenuCompatible();
                return false;
            case R.id.menu_drop_caches_and_list:
                dropStored(true);
                invalidateOptionsMenuCompatible();
                return true;
            case R.id.menu_import_gpx:
                importGpx();
                invalidateOptionsMenuCompatible();
                return false;
            case R.id.menu_create_list:
                new StoredList.UserInterface(this).promptForListCreation(getListSwitchingRunnable(), newListName);
                invalidateOptionsMenuCompatible();
                return false;
            case R.id.menu_drop_list:
                removeList(true);
                invalidateOptionsMenuCompatible();
                return false;
            case R.id.menu_rename_list:
                renameList();
                return false;
            case R.id.menu_invert_selection:
                adapter.invertSelection();
                invalidateOptionsMenuCompatible();
                return false;
            case R.id.menu_switch_list:
                selectList();
                invalidateOptionsMenuCompatible();
                return false;
            case R.id.menu_filter:
                showFilterMenu(null);
                return true;
            case R.id.menu_sort:
                final CacheComparator oldComparator = adapter.getCacheComparator();
                new ComparatorUserInterface(this).selectComparator(oldComparator, new RunnableWithArgument<CacheComparator>() {
                    @Override
                    public void run(CacheComparator selectedComparator) {
                        // selecting the same sorting twice will toggle the order
                        if (selectedComparator != null && oldComparator != null && selectedComparator.getClass().equals(oldComparator.getClass())) {
                            adapter.toggleInverseSort();
                        }
                        else {
                            // always reset the inversion for a new sorting criteria
                            adapter.resetInverseSort();
                        }
                        setComparator(selectedComparator);
                    }
                });
                return true;
            case R.id.menu_import_web:
                importWeb();
                return false;
            case R.id.menu_export:
                ExportFactory.showExportMenu(adapter.getCheckedOrAllCaches(), this);
                return false;
            case R.id.menu_remove_from_history:
                removeFromHistoryCheck();
                invalidateOptionsMenuCompatible();
                return false;
            case R.id.menu_move_to_list:
                moveCachesToOtherList();
                invalidateOptionsMenuCompatible();
                return true;
            case R.id.menu_delete_events:
                deletePastEvents();
                invalidateOptionsMenuCompatible();
                return true;
            case R.id.menu_clear_offline_logs:
                clearOfflineLogs();
                invalidateOptionsMenuCompatible();
                return true;
            default:
                return CacheListAppFactory.onMenuItemSelected(item, cacheList, this, search);
        }
    }

    public void deletePastEvents() {
        final List<Geocache> deletion = new ArrayList<Geocache>();
        for (final Geocache cache : adapter.getCheckedOrAllCaches()) {
            if (cache.isEventCache()) {
                final Date eventDate = cache.getHiddenDate();
                if (DateUtils.daysSince(eventDate.getTime()) > 0) {
                    deletion.add(cache);
                }
            }
        }
        new DropDetailsTask(false).execute(deletion.toArray(new Geocache[deletion.size()]));
    }

    public void clearOfflineLogs() {
        progress.show(this, null, res.getString(R.string.caches_clear_offlinelogs_progress), true, clearOfflineLogsHandler.obtainMessage(MSG_CANCEL));
        new ClearOfflineLogsThread(clearOfflineLogsHandler).start();
    }

    /**
     * called from the filter bar view
     */
    @Override
    public void showFilterMenu(final View view) {
        new FilterUserInterface(this).selectFilter(new RunnableWithArgument<IFilter>() {
            @Override
            public void run(IFilter selectedFilter) {
                if (selectedFilter != null) {
                    setFilter(selectedFilter);
                }
                else {
                    // clear filter
                    setFilter(null);
                }
            }
        });
    }

    private void setComparator(final CacheComparator comparator) {
        adapter.setComparator(comparator);
    }

    @Override
    public void onCreateContextMenu(final ContextMenu menu, final View view, final ContextMenu.ContextMenuInfo info) {
        super.onCreateContextMenu(menu, view, info);

        AdapterContextMenuInfo adapterInfo = null;
        try {
            adapterInfo = (AdapterContextMenuInfo) info;
        } catch (final Exception e) {
            Log.w("CacheListActivity.onCreateContextMenu", e);
        }

        if (adapterInfo == null || adapterInfo.position >= adapter.getCount()) {
            return;
        }
        final Geocache cache = adapter.getItem(adapterInfo.position);

        menu.setHeaderTitle(StringUtils.defaultIfBlank(cache.getName(), cache.getGeocode()));

        contextMenuGeocode = cache.getGeocode();

        getMenuInflater().inflate(R.menu.cache_list_context, menu);

        menu.findItem(R.id.menu_default_navigation).setTitle(NavigationAppFactory.getDefaultNavigationApplication().getName());
        final boolean hasCoords = cache.getCoords() != null;
        menu.findItem(R.id.menu_default_navigation).setVisible(hasCoords);
        menu.findItem(R.id.menu_navigate).setVisible(hasCoords);
        menu.findItem(R.id.menu_cache_details).setVisible(hasCoords);
        final boolean isOffline = cache.isOffline();
        menu.findItem(R.id.menu_drop_cache).setVisible(isOffline);
        menu.findItem(R.id.menu_move_to_list).setVisible(isOffline);
        menu.findItem(R.id.menu_export).setVisible(isOffline);
        menu.findItem(R.id.menu_refresh).setVisible(isOffline);
        menu.findItem(R.id.menu_store_cache).setVisible(!isOffline);

        LoggingUI.onPrepareOptionsMenu(menu, cache);
    }

    private void moveCachesToOtherList() {
        new StoredList.UserInterface(this).promptForListSelection(R.string.cache_menu_move_list, new RunnableWithArgument<Integer>() {

            @Override
            public void run(Integer newListId) {
                DataStore.moveToList(adapter.getCheckedOrAllCaches(), newListId);
                adapter.setSelectMode(false);

                refreshCurrentList();
            }
        }, true, listId);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ContextMenu.ContextMenuInfo info = item.getMenuInfo();

        // restore menu info for sub menu items, see
        // https://code.google.com/p/android/issues/detail?id=7139
        if (info == null) {
            info = lastMenuInfo;
            lastMenuInfo = null;
        }

        AdapterContextMenuInfo adapterInfo = null;
        try {
            adapterInfo = (AdapterContextMenuInfo) info;
        } catch (final Exception e) {
            Log.w("CacheListActivity.onContextItemSelected", e);
        }

        final Geocache cache = adapterInfo != null ? getCacheFromAdapter(adapterInfo) : null;

        // just in case the list got resorted while we are executing this code
        if (cache == null) {
            return true;
        }

        switch (item.getItemId()) {
            case R.id.menu_default_navigation:
                NavigationAppFactory.startDefaultNavigationApplication(1, this, cache);
                break;
            case R.id.menu_navigate:
                NavigationAppFactory.showNavigationMenu(this, cache, null, null);
                break;
            case R.id.menu_cache_details:
                CacheDetailActivity.startActivity(this, cache.getGeocode(), cache.getName());
                break;
            case R.id.menu_drop_cache:
                cache.drop(new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        adapter.notifyDataSetChanged();
                        refreshCurrentList();
                    }
                });
                break;
            case R.id.menu_move_to_list:
                new StoredList.UserInterface(this).promptForListSelection(R.string.cache_menu_move_list, new RunnableWithArgument<Integer>() {

                    @Override
                    public void run(Integer newListId) {
                        DataStore.moveToList(Collections.singletonList(cache), newListId);
                        adapter.setSelectMode(false);
                        refreshCurrentList();
                    }
                }, true, listId, newListName);
                break;
            case R.id.menu_store_cache:
            case R.id.menu_refresh:
                refreshStored(Collections.singletonList(cache));
                break;
            case R.id.menu_export:
                ExportFactory.showExportMenu(Collections.singletonList(cache), this);
                return false;
            default:
                // we must remember the menu info for the sub menu, there is a bug
                // in Android:
                // https://code.google.com/p/android/issues/detail?id=7139
                lastMenuInfo = info;
                LoggingUI.onMenuItemSelected(item, this, cache);
        }

        return true;
    }

    /**
     * Extract a cache from adapter data.
     *
     * @param adapterInfo
     *            an adapterInfo
     * @return the pointed cache
     */
    private Geocache getCacheFromAdapter(final AdapterContextMenuInfo adapterInfo) {
        final Geocache cache = adapter.getItem(adapterInfo.position);
        if (cache.getGeocode().equalsIgnoreCase(contextMenuGeocode)) {
            return cache;
        }

        return adapter.findCacheByGeocode(contextMenuGeocode);
    }

    private boolean setFilter(IFilter filter) {
        adapter.setFilter(filter);
        prepareFilterBar();
        updateTitle();
        invalidateOptionsMenuCompatible();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (adapter.isSelectMode()) {
                adapter.setSelectMode(false);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setAdapter() {
        if (listFooter == null) {
            if (inflater == null) {
                inflater = getLayoutInflater();
            }
            listFooter = inflater.inflate(R.layout.cacheslist_footer, null);
            listFooter.setClickable(true);
            listFooter.setOnClickListener(new MoreCachesListener());

            listFooterText = (TextView) listFooter.findViewById(R.id.more_caches);

            getListView().addFooterView(listFooter);
        }

        if (adapter == null) {
            final ListView list = getListView();

            registerForContextMenu(list);
            list.setLongClickable(true);

            adapter = new CacheListAdapter(this, cacheList, type);
            setListAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        adapter.forceSort();
        adapter.reFilter();
    }

    private void showFooterLoadingCaches() {
        if (listFooter == null) {
            return;
        }

        listFooterText.setText(res.getString(R.string.caches_more_caches_loading));
        listFooter.setClickable(false);
        listFooter.setOnClickListener(null);
    }

    private void showFooterMoreCaches() {
        if (listFooter == null) {
            return;
        }

        boolean enableMore = (type != CacheListType.OFFLINE && cacheList.size() < MAX_LIST_ITEMS);
        if (enableMore && search != null) {
            final int count = search.getTotal();
            enableMore = enableMore && count > 0 && cacheList.size() < count;
        }

        if (enableMore) {
            listFooterText.setText(res.getString(R.string.caches_more_caches) + " (" + res.getString(R.string.caches_more_caches_currently) + ": " + cacheList.size() + ")");
            listFooter.setOnClickListener(new MoreCachesListener());
        } else {
            listFooterText.setText(res.getString(CollectionUtils.isEmpty(cacheList) ? R.string.caches_no_cache : R.string.caches_more_caches_no));
            listFooter.setOnClickListener(null);
        }
        listFooter.setClickable(enableMore);
    }

    private void startGeoAndDir() {
        geoDirHandler.startGeo();
        if (Settings.isLiveMap()) {
            geoDirHandler.startDir();
        }
    }

    private void removeGeoAndDir() {
        geoDirHandler.stopGeoAndDir();
    }

    private void importGpx() {
        GpxFileListActivity.startSubActivity(this, listId);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        refreshCurrentList();
    }

    public void refreshStored(final List<Geocache> caches) {
        detailTotal = caches.size();
        if (detailTotal == 0) {
            return;
        }

        if (!Network.isNetworkConnected(getApplicationContext())) {
            showToast(getString(R.string.err_server));
            return;
        }

        if (Settings.getChooseList() && type != CacheListType.OFFLINE) {
            // let user select list to store cache in
            new StoredList.UserInterface(this).promptForListSelection(R.string.list_title,
                    new RunnableWithArgument<Integer>() {
                        @Override
                        public void run(final Integer selectedListId) {
                            refreshStored(caches, selectedListId);
                        }
                    }, true, StoredList.TEMPORARY_LIST_ID, newListName);
        } else {
            refreshStored(caches, this.listId);
        }
    }

    private void refreshStored(final List<Geocache> caches, final int storeListId) {
        detailProgress = 0;

        showProgress(false);

        final int etaTime = ((detailTotal * 25) / 60);
        String message;
        if (etaTime < 1) {
            message = res.getString(R.string.caches_downloading) + " " + res.getString(R.string.caches_eta_ltm);
        } else {
            message = res.getString(R.string.caches_downloading) + " " + etaTime + " " + res.getQuantityString(R.plurals.caches_eta_mins, etaTime);
        }

        progress.show(this, null, message, ProgressDialog.STYLE_HORIZONTAL, loadDetailsHandler.obtainMessage(MSG_CANCEL));
        progress.setMaxProgressAndReset(detailTotal);

        detailProgressTime = System.currentTimeMillis();

        threadDetails = new LoadDetailsThread(loadDetailsHandler, caches, storeListId);
        threadDetails.start();
    }

    public void removeFromHistoryCheck() {
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setCancelable(true);
        dialog.setTitle(res.getString(R.string.caches_removing_from_history));
        dialog.setMessage((adapter != null && adapter.getCheckedCount() > 0) ? res.getString(R.string.cache_remove_from_history)
                : res.getString(R.string.cache_clear_history));
        dialog.setPositiveButton(getString(android.R.string.yes), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                removeFromHistory();
                dialog.cancel();
            }
        });
        dialog.setNegativeButton(getString(android.R.string.no), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });

        final AlertDialog alert = dialog.create();
        alert.show();
    }

    public void removeFromHistory() {
        final List<Geocache> caches = adapter.getCheckedOrAllCaches();
        final String[] geocodes = new String[caches.size()];
        for (int i = 0; i < geocodes.length; i++) {
            geocodes[i] = caches.get(i).getGeocode();
        }
        final Bundle b = new Bundle();
        b.putStringArray(Intents.EXTRA_CACHELIST, geocodes);
        getSupportLoaderManager().initLoader(CacheListLoaderType.REMOVE_FROM_HISTORY.ordinal(), b, this);
    }

    public void importWeb() {
        detailProgress = 0;

        showProgress(false);
        progress.show(this, null, res.getString(R.string.web_import_waiting), true, downloadFromWebHandler.obtainMessage(MSG_CANCEL));

        threadWeb = new LoadFromWebThread(downloadFromWebHandler, listId);
        threadWeb.start();
    }

    public void dropStored(final boolean removeListAfterwards) {
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setCancelable(true);
        dialog.setTitle(res.getString(R.string.caches_drop_stored));

        if (adapter.getCheckedCount() > 0) {
            dialog.setMessage(res.getString(R.string.caches_drop_selected_ask));
        } else {
            dialog.setMessage(res.getString(R.string.caches_drop_all_ask));
        }
        dialog.setPositiveButton(getString(android.R.string.yes), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int id) {
                dropSelected(removeListAfterwards);
                dialog.cancel();
            }
        });
        dialog.setNegativeButton(getString(android.R.string.no), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });

        final AlertDialog alert = dialog.create();
        alert.show();
    }

    public void dropSelected(boolean removeListAfterwards) {
        final List<Geocache> selected = adapter.getCheckedOrAllCaches();
        new DropDetailsTask(removeListAfterwards).execute(selected.toArray(new Geocache[selected.size()]));
    }

    /**
     * Thread to refresh the cache details.
     */

    private class LoadDetailsThread extends Thread {

        final private Handler handler;
        final private int listIdLD;
        private volatile boolean needToStop = false;
        final private List<Geocache> caches;

        public LoadDetailsThread(Handler handlerIn, List<Geocache> caches, int listId) {
            handler = handlerIn;
            this.caches = caches;

            // in case of online lists, set the list id to the standard list
            this.listIdLD = Math.max(listId, StoredList.STANDARD_LIST_ID);
        }

        public void kill() {
            needToStop = true;
        }

        @Override
        public void run() {
            removeGeoAndDir();
            // First refresh caches that do not yet have static maps to get them a chance to get a copy
            // before the limit expires, unless we do not want to store offline maps.
            final List<Geocache> allCaches = Settings.isStoreOfflineMaps() ?
                    ListUtils.union(ListUtils.selectRejected(caches, Geocache.hasStaticMap),
                            ListUtils.select(caches, Geocache.hasStaticMap)) :
                    caches;

            for (final Geocache cache : allCaches) {
                if (!refreshCache(cache)) {
                    break;
                }
            }

            handler.sendEmptyMessage(MSG_RESTART_GEO_AND_DIR);
            handler.sendEmptyMessage(MSG_DONE);
        }

        /**
         * Refreshes the cache information.
         *
         * @param cache
         *            The cache to refresh
         * @return
         *         <code>false</code> if the storing was interrupted, <code>true</code> otherwise
         */
        private boolean refreshCache(Geocache cache) {
            try {
                if (needToStop) {
                    throw new InterruptedException("Stopped storing process.");
                }
                detailProgress++;
                cache.refresh(listIdLD, null);
                handler.sendEmptyMessage(cacheList.indexOf(cache));
            } catch (final InterruptedException e) {
                Log.i(e.getMessage());
                return false;
            } catch (final Exception e) {
                Log.e("CacheListActivity.LoadDetailsThread", e);
            }

            return true;
        }
    }

    private class LoadFromWebThread extends Thread {

        final private Handler handler;
        final private int listIdLFW;
        private volatile boolean needToStop = false;

        public LoadFromWebThread(Handler handlerIn, int listId) {
            handler = handlerIn;
            listIdLFW = StoredList.getConcreteList(listId);
        }

        public void kill() {
            needToStop = true;
        }

        @Override
        public void run() {

            removeGeoAndDir();

            int delay = -1;
            int times = 0;

            int ret = MSG_DONE;
            while (!needToStop && times < 3 * 60 / 5) { // maximum: 3 minutes, every 5 seconds
                //download new code
                String deviceCode = Settings.getWebDeviceCode();
                if (deviceCode == null) {
                    deviceCode = "";
                }
                final Parameters params = new Parameters("code", deviceCode);
                final HttpResponse responseFromWeb = Network.getRequest("http://send2.cgeo.org/read.html", params);

                if (responseFromWeb != null && responseFromWeb.getStatusLine().getStatusCode() == 200) {
                    final String response = Network.getResponseData(responseFromWeb);
                    if (response != null && response.length() > 2) {
                        delay = 1;
                        handler.sendMessage(handler.obtainMessage(1, response));
                        yield();

                        Geocache.storeCache(null, response, listIdLFW, false, null);

                        handler.sendMessage(handler.obtainMessage(2, response));
                        yield();
                    } else if ("RG".equals(response)) {
                        //Server returned RG (registration) and this device no longer registered.
                        Settings.setWebNameCode(null, null);
                        ret = -3;
                        needToStop = true;
                        break;
                    } else {
                        delay = 0;
                        handler.sendEmptyMessage(0);
                        yield();
                    }
                }
                if (responseFromWeb == null || responseFromWeb.getStatusLine().getStatusCode() != 200) {
                    ret = -2;
                    needToStop = true;
                    break;
                }

                try {
                    yield();
                    if (delay == 0) {
                        sleep(5000); //No caches 5s
                        times++;
                    } else {
                        sleep(500); //Cache was loaded 0.5s
                        times = 0;
                    }
                } catch (final InterruptedException e) {
                    Log.e("CacheListActivity.LoadFromWebThread.sleep", e);
                }
            }

            handler.sendEmptyMessage(ret);

            startGeoAndDir();
        }
    }

    private class DropDetailsTask extends AsyncTaskWithProgress<Geocache, Void> {

        private final boolean removeListAfterwards;

        public DropDetailsTask(boolean removeListAfterwards) {
            super(CacheListActivity.this, null, res.getString(R.string.caches_drop_progress), true);
            this.removeListAfterwards = removeListAfterwards;
        }

        @Override
        protected Void doInBackgroundInternal(Geocache[] caches) {
            removeGeoAndDir();
            DataStore.markDropped(Arrays.asList(caches));
            startGeoAndDir();
            return null;
        }

        @Override
        protected void onPostExecuteInternal(Void result) {
            // remove list in UI because of toast
            if (removeListAfterwards) {
                removeList(false);
            }

            adapter.setSelectMode(false);
            refreshCurrentList();
            replaceCacheListFromSearch();
        }

    }

    private class ClearOfflineLogsThread extends Thread {

        final private Handler handler;
        final private List<Geocache> selected;

        public ClearOfflineLogsThread(Handler handlerIn) {
            handler = handlerIn;
            selected = adapter.getCheckedOrAllCaches();
        }

        @Override
        public void run() {
            DataStore.clearLogsOffline(selected);
            handler.sendEmptyMessage(MSG_DONE);
        }
    }

    private class MoreCachesListener implements View.OnClickListener {

        @Override
        public void onClick(View arg0) {
            showProgress(true);
            showFooterLoadingCaches();
            listFooter.setOnClickListener(null);

            getSupportLoaderManager().restartLoader(CacheListLoaderType.NEXT_PAGE.ordinal(), null, CacheListActivity.this);
        }
    }

    private void hideLoading() {
        final ListView list = getListView();
        if (list.getVisibility() == View.GONE) {
            list.setVisibility(View.VISIBLE);
            final View loading = findViewById(R.id.loading);
            loading.setVisibility(View.GONE);
        }
    }

    public void selectList() {
        if (!type.canSwitch) {
            return;
        }
        new StoredList.UserInterface(this).promptForListSelection(R.string.list_title, getListSwitchingRunnable());
    }

    @NonNull
    private RunnableWithArgument<Integer> getListSwitchingRunnable() {
        return new RunnableWithArgument<Integer>() {

            @Override
            public void run(final Integer selectedListId) {
                switchListById(selectedListId);
            }
        };
    }

    public void switchListById(int id) {
        if (id < 0) {
            return;
        }

        final StoredList list = DataStore.getList(id);
        if (list == null) {
            return;
        }

        listId = list.id;
        title = list.title;

        Settings.saveLastList(listId);

        showProgress(true);
        showFooterLoadingCaches();
        DataStore.moveToList(adapter.getCheckedCaches(), listId);

        currentLoader = (OfflineGeocacheListLoader) getSupportLoaderManager().initLoader(CacheListType.OFFLINE.ordinal(), new Bundle(), this);
        currentLoader.reset();
        ((OfflineGeocacheListLoader) currentLoader).setListId(listId);
        ((OfflineGeocacheListLoader) currentLoader).setSearchCenter(coords);
        adapter.setComparator(null); // delete current sorting
        currentLoader.startLoading();

        invalidateOptionsMenuCompatible();
    }

    private void renameList() {
        new StoredList.UserInterface(this).promptForListRename(listId, new Runnable() {

            @Override
            public void run() {
                refreshCurrentList();
            }
        });
    }

    private void removeListInternal() {
        if (DataStore.removeList(listId)) {
            showToast(res.getString(R.string.list_dialog_remove_ok));
            switchListById(StoredList.STANDARD_LIST_ID);
        } else {
            showToast(res.getString(R.string.list_dialog_remove_err));
        }
    }

    private void removeList(final boolean askForConfirmation) {
        // if there are no caches on this list, don't bother the user with questions.
        // there is no harm in deleting the list, he could recreate it easily
        if (CollectionUtils.isEmpty(cacheList)) {
            removeListInternal();
            return;
        }

        if (!askForConfirmation) {
            removeListInternal();
            return;
        }

        // ask him, if there are caches on the list
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle(R.string.list_dialog_remove_title);
        alert.setMessage(R.string.list_dialog_remove_description);
        alert.setPositiveButton(R.string.list_dialog_remove, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int whichButton) {
                removeListInternal();
            }
        });
        alert.setNegativeButton(res.getString(R.string.list_dialog_cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int whichButton) {
                dialog.dismiss();
            }
        });

        alert.show();
    }

    /**
     * @param view
     *            unused here but needed since this method is referenced from XML layout
     */
    public void goMap(View view) {
        if (search == null || CollectionUtils.isEmpty(cacheList)) {
            showToast(res.getString(R.string.warn_no_cache_coord));

            return;
        }

        // apply filter settings (if there's a filter)
        final Set<String> geocodes = new HashSet<String>();
        for (final Geocache cache : adapter.getFilteredList()) {
            geocodes.add(cache.getGeocode());
        }

        final SearchResult searchToUse = new SearchResult(geocodes);
        final int count = searchToUse.getCount();
        String mapTitle = title;
        if (count > 0) {
            mapTitle = title + " [" + count + "]";
        }
        CGeoMap.startActivitySearch(this, searchToUse, mapTitle);
    }

    private void refreshCurrentList() {
        switchListById(listId);
    }

    public static void startActivityOffline(final Context context) {
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.OFFLINE);
        context.startActivity(cachesIntent);
    }

    public static void startActivityOwner(final AbstractActivity context, final String userName) {
        if (!isValidUsername(context, userName)) {
            return;
        }
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.OWNER);
        cachesIntent.putExtra(Intents.EXTRA_USERNAME, userName);
        context.startActivity(cachesIntent);
    }

    private static boolean isValidUsername(AbstractActivity context, String username) {
        if (StringUtils.isBlank(username)) {
            context.showToast(CgeoApplication.getInstance().getString(R.string.warn_no_username));
            return false;
        }
        return true;
    }

    public static void startActivityUserName(final AbstractActivity context, final String userName) {
        if (!isValidUsername(context, userName)) {
            return;
        }
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.USERNAME);
        cachesIntent.putExtra(Intents.EXTRA_USERNAME, userName);
        context.startActivity(cachesIntent);
    }

    private void prepareFilterBar() {
        if (Settings.getCacheType() != CacheType.ALL || adapter.isFiltered()) {
            final StringBuilder output = new StringBuilder(Settings.getCacheType().getL10n());

            if (adapter.isFiltered()) {
                output.append(", ").append(adapter.getFilterName());
            }

            ((TextView) findViewById(R.id.filter_text)).setText(output.toString());
            findViewById(R.id.filter_bar).setVisibility(View.VISIBLE);
        }
        else {
            findViewById(R.id.filter_bar).setVisibility(View.GONE);
        }
    }

    public static void startActivityNearest(final AbstractActivity context, final Geopoint coordsNow) {
        if (!isValidCoords(context, coordsNow)) {
            return;
        }
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.NEAREST);
        cachesIntent.putExtra(Intents.EXTRA_COORDS, coordsNow);
        context.startActivity(cachesIntent);
    }

    public static void startActivityHistory(Context context) {
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.HISTORY);
        context.startActivity(cachesIntent);
    }

    public static void startActivityAddress(final Context context, final Geopoint coords, final String address) {
        final Intent addressIntent = new Intent(context, CacheListActivity.class);
        addressIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.ADDRESS);
        addressIntent.putExtra(Intents.EXTRA_COORDS, coords);
        addressIntent.putExtra(Intents.EXTRA_ADDRESS, address);
        context.startActivity(addressIntent);
    }

    public static void startActivityCoordinates(final AbstractActivity context, final Geopoint coords) {
        if (!isValidCoords(context, coords)) {
            return;
        }
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.COORDINATE);
        cachesIntent.putExtra(Intents.EXTRA_COORDS, coords);
        context.startActivity(cachesIntent);
    }

    private static boolean isValidCoords(AbstractActivity context, Geopoint coords) {
        if (coords == null) {
            context.showToast(CgeoApplication.getInstance().getString(R.string.warn_no_coordinates));
            return false;
        }
        return true;
    }

    public static void startActivityKeyword(final AbstractActivity context, final String keyword) {
        if (keyword == null) {
            context.showToast(CgeoApplication.getInstance().getString(R.string.warn_no_keyword));
            return;
        }
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.KEYWORD);
        cachesIntent.putExtra(Intents.EXTRA_KEYWORD, keyword);
        context.startActivity(cachesIntent);
    }

    public static void startActivityMap(final Context context, final SearchResult search) {
        final Intent cachesIntent = new Intent(context, CacheListActivity.class);
        cachesIntent.putExtra(Intents.EXTRA_LIST_TYPE, CacheListType.MAP);
        cachesIntent.putExtra(Intents.EXTRA_SEARCH, search);
        context.startActivity(cachesIntent);
    }

    // Loaders

    @Override
    public Loader<SearchResult> onCreateLoader(int type, Bundle extras) {
        if (type >= CacheListLoaderType.values().length) {
            throw new IllegalArgumentException("invalid loader type " + type);
        }
        final CacheListLoaderType enumType = CacheListLoaderType.values()[type];
        AbstractSearchLoader loader = null;
        switch (enumType) {
            case OFFLINE:
                listId = Settings.getLastList();
                if (listId <= StoredList.TEMPORARY_LIST_ID) {
                    listId = StoredList.STANDARD_LIST_ID;
                    title = res.getString(R.string.stored_caches_button);
                } else {
                    final StoredList list = DataStore.getList(listId);
                    // list.id may be different if listId was not valid
                    listId = list.id;
                    title = list.title;
                }

                loader = new OfflineGeocacheListLoader(this.getBaseContext(), coords, listId);

                break;
            case HISTORY:
                title = res.getString(R.string.caches_history);
                loader = new HistoryGeocacheListLoader(app, coords);
                break;
            case NEAREST:
                title = res.getString(R.string.caches_nearby);
                loader = new CoordsGeocacheListLoader(app, coords);
                break;
            case COORDINATE:
                title = coords.toString();
                loader = new CoordsGeocacheListLoader(app, coords);
                break;
            case KEYWORD:
                final String keyword = extras.getString(Intents.EXTRA_KEYWORD);
                rememberTerm(keyword);
                loader = new KeywordGeocacheListLoader(app, keyword);
                break;
            case ADDRESS:
                final String address = extras.getString(Intents.EXTRA_ADDRESS);
                if (StringUtils.isNotBlank(address)) {
                    rememberTerm(address);
                } else {
                    title = coords.toString();
                }
                if (coords != null) {
                    loader = new CoordsGeocacheListLoader(app, coords);
                    }
                else {
                    loader = new AddressGeocacheListLoader(app, address);
                }
                break;
            case USERNAME:
                final String username = extras.getString(Intents.EXTRA_USERNAME);
                rememberTerm(username);
                loader = new UsernameGeocacheListLoader(app, username);
                break;
            case OWNER:
                final String ownerName = extras.getString(Intents.EXTRA_USERNAME);
                rememberTerm(ownerName);
                loader = new OwnerGeocacheListLoader(app, ownerName);
                break;
            case MAP:
                //TODO Build Null loader
                title = res.getString(R.string.map_map);
                search = (SearchResult) extras.get(Intents.EXTRA_SEARCH);
                replaceCacheListFromSearch();
                loadCachesHandler.sendMessage(Message.obtain());
                break;
            case REMOVE_FROM_HISTORY:
                title = res.getString(R.string.caches_history);
                loader = new RemoveFromHistoryLoader(app, extras.getStringArray(Intents.EXTRA_CACHELIST), coords);
                break;
            case NEXT_PAGE:
                loader = new NextPageGeocacheListLoader(app, search);
                break;
            default:
                throw new IllegalStateException();
        }
        setTitle(title);
        showProgress(true);
        showFooterLoadingCaches();

        if (loader != null) {
            loader.setRecaptchaHandler(new SearchHandler(this, res, loader));
        }
        return loader;
    }

    private void rememberTerm(String term) {
        // set the title of the activity
        title = term;
        // and remember this term for potential use in list creation
        newListName = term;
    }

    @Override
    public void onLoadFinished(Loader<SearchResult> arg0, SearchResult searchIn) {
        // The database search was moved into the UI call intentionally. If this is done before the runOnUIThread,
        // then we have 2 sets of caches in memory. This can lead to OOM for huge cache lists.
        if (searchIn != null) {
            cacheList.clear();
            final Set<Geocache> cachesFromSearchResult = searchIn.getCachesFromSearchResult(LoadFlags.LOAD_CACHE_OR_DB);
            cacheList.addAll(cachesFromSearchResult);
            search = searchIn;
            adapter.reFilter();
            adapter.setInitialComparator();
            adapter.forceSort();
            adapter.notifyDataSetChanged();
            updateTitle();
            showFooterMoreCaches();
        }
        showProgress(false);
        hideLoading();
    }

    @Override
    public void onLoaderReset(Loader<SearchResult> arg0) {
        //Not interesting
    }
}

