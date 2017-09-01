/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Dialog
import android.app.LoaderManager
import android.content.*
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.support.design.widget.Snackbar
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.*
import android.widget.*
import at.bitfire.davdroid.App
import at.bitfire.davdroid.CustomCertificates
import at.bitfire.davdroid.DavService
import at.bitfire.davdroid.R
import at.bitfire.davdroid.log.Logger
import at.bitfire.davdroid.model.CollectionInfo
import at.bitfire.davdroid.model.ServiceDB
import at.bitfire.davdroid.model.ServiceDB.*
import at.bitfire.davdroid.model.ServiceDB.Collections
import at.bitfire.davdroid.resource.LocalAddressBook
import at.bitfire.davdroid.resource.LocalTaskList
import at.bitfire.ical4android.TaskProvider
import at.bitfire.vcard4android.ContactsStorageException
import kotlinx.android.synthetic.main.activity_account.*
import java.util.*
import java.util.logging.Level

class AccountActivity: AppCompatActivity(), Toolbar.OnMenuItemClickListener, PopupMenu.OnMenuItemClickListener, LoaderManager.LoaderCallbacks<AccountActivity.AccountInfo> {

    companion object {
        @JvmField val EXTRA_ACCOUNT = "account"

        private fun requestSync(account: Account) {
            val authorities = arrayOf(
                    App.addressBooksAuthority,
                    CalendarContract.AUTHORITY,
                    TaskProvider.ProviderName.OpenTasks.authority
            )

            for (authority in authorities) {
                val extras = Bundle(2)
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)        // manual sync
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)     // run immediately (don't queue)
                ContentResolver.requestSync(account, authority, extras)
            }
        }

    }

    lateinit var account: Account
    var accountInfo: AccountInfo? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        account = intent.getParcelableExtra(EXTRA_ACCOUNT)
        title = account.name

        setContentView(R.layout.activity_account)

        val icMenu = if (Build.VERSION.SDK_INT >= 21)
            getDrawable(R.drawable.ic_menu_light)
        else
            resources.getDrawable(R.drawable.ic_menu_light)

        // CardDAV toolbar
        carddav_menu.overflowIcon = icMenu
        carddav_menu.inflateMenu(R.menu.carddav_actions)
        carddav_menu.setOnMenuItemClickListener(this)

        // CalDAV toolbar
        caldav_menu.overflowIcon = icMenu
        caldav_menu.inflateMenu(R.menu.caldav_actions)
        caldav_menu.setOnMenuItemClickListener(this)

        // load CardDAV/CalDAV collections
        loaderManager.initLoader(0, intent.extras, this)
    }

    override fun onPause() {
        super.onPause()
        CustomCertificates.certManager?.let { it.appInForeground = false }
    }

    override fun onResume() {
        super.onResume()
        CustomCertificates.certManager?.let { it.appInForeground = true }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_account, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val itemRename = menu.findItem(R.id.rename_account)
        // renameAccount is available for API level 21+
        itemRename.isVisible = Build.VERSION.SDK_INT >= 21
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sync_now ->
                requestSync()
            R.id.settings -> {
                val intent = Intent(this, AccountSettingsActivity::class.java)
                intent.putExtra(AccountSettingsActivity.EXTRA_ACCOUNT, account)
                startActivity(intent)
            }
            R.id.rename_account ->
                RenameAccountFragment.newInstance(account).show(supportFragmentManager, null)
            R.id.delete_account -> {
                AlertDialog.Builder(this)
                        .setIcon(R.drawable.ic_error_dark)
                        .setTitle(R.string.account_delete_confirmation_title)
                        .setMessage(R.string.account_delete_confirmation_text)
                        .setNegativeButton(android.R.string.no, null)
                        .setPositiveButton(android.R.string.yes, { _, _ ->
                            deleteAccount()
                        })
                        .show()
            }
            else ->
                return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.refresh_address_books ->
                accountInfo?.carddav?.let { carddav ->
                    val intent = Intent(this, DavService::class.java)
                    intent.action = DavService.ACTION_REFRESH_COLLECTIONS
                    intent.putExtra(DavService.EXTRA_DAV_SERVICE_ID, carddav.id)
                    startService(intent)
                }
            R.id.create_address_book -> {
                val intent = Intent(this, CreateAddressBookActivity::class.java)
                intent.putExtra(CreateAddressBookActivity.EXTRA_ACCOUNT, account)
                startActivity(intent)
            }
            R.id.refresh_calendars ->
                accountInfo?.caldav?.let { caldav ->
                    val intent = Intent(this, DavService::class.java)
                    intent.action = DavService.ACTION_REFRESH_COLLECTIONS
                    intent.putExtra(DavService.EXTRA_DAV_SERVICE_ID, caldav.id)
                    startService(intent)
                }
            R.id.create_calendar -> {
                val intent = Intent(this, CreateCalendarActivity::class.java)
                intent.putExtra(CreateCalendarActivity.EXTRA_ACCOUNT, account)
                startActivity(intent)
            }
        }
        return false
    }


    private val onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<CollectionInfo>
        val info = adapter.getItem(position)

        val nowChecked = !info.selected

        OpenHelper(this@AccountActivity).use { dbHelper ->
            val db = dbHelper.writableDatabase
            db.beginTransactionNonExclusive()

            val values = ContentValues(1)
            values.put(Collections.SYNC, if (nowChecked) 1 else 0)
            db.update(Collections._TABLE, values, "${Collections.ID}=?", arrayOf(info.id.toString()))

            db.setTransactionSuccessful()
            db.endTransaction()

            info.selected = nowChecked
            adapter.notifyDataSetChanged()
        }
    }

    private val onItemLongClickListener = AdapterView.OnItemLongClickListener { parent, view, position, _ ->
        val list = parent as ListView
        val adapter = list.adapter as ArrayAdapter<CollectionInfo>
        val info = adapter.getItem(position)

        val popup = PopupMenu(this@AccountActivity, view)
        popup.inflate(R.menu.account_collection_operations)
        popup.setOnMenuItemClickListener({ item ->
            when (item.itemId) {
                R.id.delete_collection ->
                    DeleteCollectionFragment.ConfirmDeleteCollectionFragment.newInstance(account, info).show(getSupportFragmentManager(), null);
            }
            true
        })
        popup.show()

        // long click was handled
        true
    }


    /* LOADERS AND LOADED DATA */

    class AccountInfo {
        var carddav: ServiceInfo? = null
        var caldav: ServiceInfo? = null

        class ServiceInfo {
            var id: Long? = null
            var refreshing = false

            var hasHomeSets = false
            var collections = listOf<CollectionInfo>()
        }
    }

    override fun onCreateLoader(id: Int, args: Bundle?) =
            AccountLoader(this, account)

    fun reload() {
        loaderManager.restartLoader(0, intent.extras, this)
    }

    override fun onLoadFinished(loader: Loader<AccountInfo>, info: AccountInfo?) {
        accountInfo = info

        carddav.visibility = info?.carddav?.let { carddav ->
            carddav_refreshing.visibility = if (carddav.refreshing) View.VISIBLE else View.GONE

            address_books.isEnabled = !carddav.refreshing
            address_books.alpha = if (carddav.refreshing) 0.5f else 1f

            carddav_menu.menu.findItem(R.id.create_address_book).isEnabled = carddav.hasHomeSets

            val adapter = AddressBookAdapter(this)
            adapter.addAll(carddav.collections)
            address_books.adapter = adapter
            address_books.onItemClickListener = onItemClickListener
            address_books.onItemLongClickListener = onItemLongClickListener

            View.VISIBLE
        } ?: View.GONE

        caldav.visibility = info?.caldav?.let { caldav ->
            caldav_refreshing.visibility = if (caldav.refreshing) View.VISIBLE else View.GONE

            calendars.isEnabled = !caldav.refreshing
            calendars.alpha = if (caldav.refreshing) 0.5f else 1f

            caldav_menu.menu.findItem(R.id.create_calendar).isEnabled = caldav.hasHomeSets

            val adapter = CalendarAdapter(this)
            adapter.addAll(caldav.collections)
            calendars.adapter = adapter
            calendars.onItemClickListener = onItemClickListener
            calendars.onItemLongClickListener = onItemLongClickListener

            View.VISIBLE
        } ?: View.GONE
    }

    override fun onLoaderReset(loader: Loader<AccountInfo>) {
        address_books?.adapter = null
        calendars?.adapter = null
    }


    class AccountLoader(
            context: Context,
            val account: Account
    ): AsyncTaskLoader<AccountInfo>(context), DavService.RefreshingStatusListener, ServiceConnection, SyncStatusObserver {

        private var davService: DavService.InfoBinder? = null
        private lateinit var syncStatusListener: Any

        override fun onStartLoading() {
            syncStatusListener = ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE, this)
            context.bindService(Intent(context, DavService::class.java), this, Context.BIND_AUTO_CREATE)
        }

        override fun onStopLoading() {
            davService?.removeRefreshingStatusListener(this)
            context.unbindService(this)
            ContentResolver.removeStatusChangeListener(syncStatusListener)
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            davService = service as DavService.InfoBinder
            service.addRefreshingStatusListener(this, false)

            forceLoad()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            davService = null
        }

        override fun onDavRefreshStatusChanged(id: Long, refreshing: Boolean) =
                forceLoad()

        override fun onStatusChanged(which: Int) =
                forceLoad()

        override fun loadInBackground(): AccountInfo {
            val info = AccountInfo()

            OpenHelper(context).use { dbHelper ->
                val db = dbHelper.readableDatabase
                db.query(
                        Services._TABLE,
                        arrayOf(Services.ID, Services.SERVICE),
                        "${Services.ACCOUNT_NAME}=?", arrayOf(account.name),
                        null, null, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        when (cursor.getString(1)) {
                            Services.SERVICE_CARDDAV -> {
                                val carddav = AccountInfo.ServiceInfo()
                                info.carddav = carddav
                                carddav.id = id
                                carddav.refreshing =
                                        davService?.isRefreshing(id) ?: false ||
                                        ContentResolver.isSyncActive(account, App.addressBooksAuthority)

                                val accountManager = AccountManager.get(context)
                                for (addrBookAccount in accountManager.getAccountsByType(App.addressBookAccountType)) {
                                    val addressBook = LocalAddressBook(context, addrBookAccount, null)
                                    try {
                                        if (account == addressBook.getMainAccount())
                                            carddav.refreshing = carddav.refreshing || ContentResolver.isSyncActive(addrBookAccount, ContactsContract.AUTHORITY)
                                    } catch(e: ContactsStorageException) {
                                    }
                                }

                                carddav.hasHomeSets = hasHomeSets(db, id)
                                carddav.collections = readCollections(db, id)
                            }
                            Services.SERVICE_CALDAV -> {
                                val caldav = AccountInfo.ServiceInfo()
                                info.caldav = caldav
                                caldav.id = id
                                caldav.refreshing =
                                        davService?.isRefreshing(id) ?: false ||
                                        ContentResolver.isSyncActive(account, CalendarContract.AUTHORITY) ||
                                        ContentResolver.isSyncActive(account, TaskProvider.ProviderName.OpenTasks.authority)
                                caldav.hasHomeSets = hasHomeSets(db, id)
                                caldav.collections = readCollections(db, id)
                            }
                        }
                    }
                }

            }
            return info
        }

        private fun hasHomeSets(db: SQLiteDatabase, service: Long): Boolean {
            db.query(ServiceDB.HomeSets._TABLE, null, "${ServiceDB.HomeSets.SERVICE_ID}=?",
                    arrayOf(service.toString()), null, null, null)?.use { cursor ->
                return cursor.count > 0
            }
            return false
        }

        private fun readCollections(db: SQLiteDatabase, service: Long): List<CollectionInfo>  {
            val collections = LinkedList<CollectionInfo>()
            db.query(Collections._TABLE, null, Collections.SERVICE_ID + "=?", arrayOf(service.toString()),
                    null, null, "${Collections.SUPPORTS_VEVENT} DESC,${Collections.DISPLAY_NAME}").use { cursor ->
                while (cursor.moveToNext()) {
                    val values = ContentValues(cursor.columnCount)
                    DatabaseUtils.cursorRowToContentValues(cursor, values)
                    collections.add(CollectionInfo(values))
                }
            }
            return collections
        }

    }


    /* LIST ADAPTERS */

    class AddressBookAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_carddav_item) {

        override fun getView(position: Int, v: View?, parent: ViewGroup?): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_carddav_item, parent, false)
            val info = getItem(position)

            val checked: CheckBox = v.findViewById(R.id.checked)
            checked.isChecked = info.selected

            var tv: TextView = v.findViewById(R.id.title)
            tv.text = if (!info.displayName.isNullOrBlank()) info.displayName else info.url

            tv = v.findViewById(R.id.description)
            if (info.description.isNullOrBlank())
                tv.visibility = View.GONE
            else {
                tv.visibility = View.VISIBLE
                tv.text = info.description
            }

            tv = v.findViewById(R.id.read_only)
            tv.visibility = if (info.readOnly) View.VISIBLE else View.GONE

            return v
        }
    }

    class CalendarAdapter(
            context: Context
    ): ArrayAdapter<CollectionInfo>(context, R.layout.account_caldav_item) {

        override fun getView(position: Int, v: View?, parent: ViewGroup?): View {
            val v = v ?: LayoutInflater.from(context).inflate(R.layout.account_caldav_item, parent, false)
            val info = getItem(position)

            val checked: CheckBox = v.findViewById(R.id.checked)
            checked.isChecked = info.selected

            val vColor: View = v.findViewById(R.id.color)
            vColor.visibility = info.color?.let {
                vColor.setBackgroundColor(it)
                View.VISIBLE
            } ?: View.GONE

            var tv: TextView = v.findViewById(R.id.title)
            tv.text = if (!info.displayName.isNullOrBlank()) info.displayName else info.url

            tv = v.findViewById(R.id.description)
            if (info.description.isNullOrBlank())
                tv.visibility = View.GONE
            else {
                tv.visibility = View.VISIBLE
                tv.text = info.description
            }

            tv = v.findViewById(R.id.read_only)
            tv.visibility = if (info.readOnly) View.VISIBLE else View.GONE

            tv = v.findViewById(R.id.events)
            tv.visibility = if (info.supportsVEVENT) View.VISIBLE else View.GONE

            tv = v.findViewById(R.id.tasks)
            tv.visibility = if (info.supportsVTODO) View.VISIBLE else View.GONE

            return v
        }
    }


    /* DIALOG FRAGMENTS */

    class RenameAccountFragment: DialogFragment() {

        companion object {

            val ARG_ACCOUNT = "account"

            fun newInstance(account: Account): RenameAccountFragment {
                val fragment = RenameAccountFragment()
                val args = Bundle(1)
                args.putParcelable(ARG_ACCOUNT, account)
                fragment.arguments = args
                return fragment
            }

        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val oldAccount: Account = arguments.getParcelable(ARG_ACCOUNT)

            val editText = EditText(activity)
            editText.setText(oldAccount.name)

            return AlertDialog.Builder(activity)
                    .setTitle(R.string.account_rename)
                    .setMessage(R.string.account_rename_new_name)
                    .setView(editText)
                    .setPositiveButton(R.string.account_rename_rename, DialogInterface.OnClickListener { dialog, which ->
                        val newName = editText.text.toString()

                        if (newName == oldAccount.name)
                            return@OnClickListener

                        val accountManager = AccountManager.get(activity)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                            accountManager.renameAccount(oldAccount, newName, { _ ->
                                Logger.log.info("Updating account name references")

                                // cancel maybe running synchronization
                                ContentResolver.cancelSync(oldAccount, null)
                                for (addrBookAccount in accountManager.getAccountsByType(App.addressBookAccountType))
                                    ContentResolver.cancelSync(addrBookAccount, null)

                                // update account name references in database
                                OpenHelper(activity).use { dbHelper ->
                                    ServiceDB.onRenameAccount(dbHelper.getWritableDatabase(), oldAccount.name, newName)
                                }

                                // update main account of address book accounts
                                try {
                                    for (addrBookAccount in accountManager.getAccountsByType(App.addressBookAccountType)) {
                                        val provider = activity.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
                                        try {
                                            if (provider != null) {
                                                val addressBook = LocalAddressBook(activity, addrBookAccount, provider)
                                                if (oldAccount == addressBook.getMainAccount())
                                                    addressBook.setMainAccount(Account(newName, oldAccount.type))
                                            }
                                        } finally {
                                            if (Build.VERSION.SDK_INT >= 24)
                                                provider?.close()
                                            else
                                                provider?.release()
                                        }

                                    }
                                } catch(e: ContactsStorageException) {
                                    Logger.log.log(Level.SEVERE, "Couldn't update address book accounts", e)
                                }

                                // calendar provider doesn't allow changing account_name of Events
                                // (all events will have to be downloaded again)

                                // update account_name of local tasks
                                try {
                                    LocalTaskList.onRenameAccount(activity.contentResolver, oldAccount.name, newName)
                                } catch(e: Exception) {
                                    Logger.log.log(Level.SEVERE, "Couldn't propagate new account name to tasks provider", e)
                                }

                                // synchronize again
                                requestSync(Account(newName, oldAccount.type))
                            }, null)
                        activity.finish()
                    })
                    .setNegativeButton(android.R.string.cancel, { _, _ -> })
                    .create()
        }
    }


    /* USER ACTIONS */

    private fun deleteAccount() {
        val accountManager = AccountManager.get(this)

        if (Build.VERSION.SDK_INT >= 22)
            accountManager.removeAccount(account, this, { future ->
                try {
                    if (future.result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT))
                        finish()
                } catch(e: Exception) {
                    Logger.log.log(Level.SEVERE, "Couldn't remove account", e)
                }
            }, null)
        else
            accountManager.removeAccount(account, { future ->
                try {
                    if (future.result)
                        finish()
                } catch (e: Exception) {
                    Logger.log.log(Level.SEVERE, "Couldn't remove account", e)
                }
            }, null)
    }

    private fun requestSync() {
        requestSync(account)
        Snackbar.make(findViewById(R.id.parent), R.string.account_synchronizing_now, Snackbar.LENGTH_LONG).show()
    }

}