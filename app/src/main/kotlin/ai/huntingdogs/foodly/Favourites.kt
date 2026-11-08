package ai.huntingdogs.foodly

import android.content.Context

/** Favourites belong to the signed-in account and are kept ON THE DEVICE (SharedPreferences), so they
 *  survive an app restart — the client-side persistence a QA run checks against what the UI shows. */
object Favourites {
  private const val PREFS = "foodly_favourites"

  fun load(context: Context, userId: String): MutableSet<String> {
    val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(userId, emptySet())
    return stored?.toMutableSet() ?: mutableSetOf()
  }

  fun save(context: Context, userId: String, ids: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(userId, ids).apply()
  }
}
