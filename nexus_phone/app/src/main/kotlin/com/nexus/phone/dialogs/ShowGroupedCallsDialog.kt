package com.nexus.phone.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.viewBinding
import com.nexus.phone.activities.SimpleActivity
import com.nexus.phone.adapters.RecentCallsAdapter
import com.nexus.phone.databinding.DialogShowGroupedCallsBinding
import com.nexus.phone.models.RecentCall

class ShowGroupedCallsDialog(val activity: BaseSimpleActivity, recentCalls: List<RecentCall>) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogShowGroupedCallsBinding::inflate)

    init {
        activity.runOnUiThread {
            RecentCallsAdapter(
                activity = activity as SimpleActivity,
                recyclerView = binding.selectGroupedCallsList,
                refreshItemsListener = null,
                showOverflowMenu = false,
                itemClick = {}
            ).apply {
                binding.selectGroupedCallsList.adapter = this
                updateItems(recentCalls)
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
