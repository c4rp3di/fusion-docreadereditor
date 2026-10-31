package com.docreader.editor.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** CreateDocument con tipo MIME y nombre sugerido dinámicos: input = (mime, nombre). */
class CreateDocAs : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
