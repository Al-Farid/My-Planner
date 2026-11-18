package io.github.alfarid.planner

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore

/** Your Firebase project and planner address. These identify the project; they are not passwords. */
object Config {
    const val API_KEY = "AIzaSyD0P0DrfHLwHjLL-pB8egndbzcwC7oDCfk"
    const val PROJECT_ID = "my-planner-d4061"
    const val APP_ID = "1:1058970057229:web:21e519174088c2ce997344"
    const val PLANNER_URL = "https://al-farid.github.io/My-Planner/#week"

    private const val APP_NAME = "planner"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("planner", Context.MODE_PRIVATE)

    fun db(ctx: Context): FirebaseFirestore {
        val appCtx = ctx.applicationContext
        val app = FirebaseApp.getApps(appCtx).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                appCtx,
                FirebaseOptions.Builder()
                    .setApiKey(API_KEY)
                    .setProjectId(PROJECT_ID)
                    .setApplicationId(APP_ID)
                    .build(),
                APP_NAME
            )
        return FirebaseFirestore.getInstance(app)
    }

    /** The widget code from the planner: letters and digits only. */
    fun cleanCode(raw: String?): String? {
        val c = (raw ?: "").trim().filter { it.isLetterOrDigit() }
        return if (c.length >= 20) c else null
    }
}
