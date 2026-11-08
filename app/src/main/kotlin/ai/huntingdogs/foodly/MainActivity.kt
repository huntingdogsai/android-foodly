package ai.huntingdogs.foodly

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * A standalone native Android Foodly demo (sign in → products + favourites + search → cart → checkout),
 * one Activity switching screens with programmatic Views. The same flow and backend as the other Foodly
 * demos. It contains NO Operator SDK — the watchdog build adds it automatically.
 */
class MainActivity : Activity() {
  private val io = Executors.newSingleThreadExecutor()
  private lateinit var column: LinearLayout
  private lateinit var totals: TextView

  private var user: FoodlyApi.User? = null
  private var products: List<FoodlyApi.Product> = emptyList()
  private var favourites: MutableSet<String> = mutableSetOf()
  private var cart: List<FoodlyApi.CartItem> = emptyList()
  private var discount = 0
  private val draft = JSONObject()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(24), dp(16), dp(24))
    }
    setContentView(ScrollView(this).apply { addView(column) })
    showSignIn()
  }

  private fun showSignIn() {
    column.removeAllViews()
    column.addView(title("Sign in to Foodly"))
    val email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT)
    val password = input("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT)
    column.addView(email)
    column.addView(password)
    column.addView(button("Sign in") {
      dispatch({ FoodlyApi.signIn(email.text.toString().trim(), password.text.toString()) }) { signedIn ->
        user = signedIn
        draft.put("fullName", signedIn.name).put("email", signedIn.email)
        favourites = Favourites.load(this, signedIn.id)
        dispatch({ FoodlyApi.searchProducts() }) { list ->
          products = list
          showHome()
        }
      }
    })
  }

  private fun showHome() {
    column.removeAllViews()
    column.addView(header())
    val search = input("Search…", InputType.TYPE_CLASS_TEXT)
    column.addView(rowOf(search.grow(), button("Find") {
      dispatch({ FoodlyApi.searchProducts(search.text.toString().trim()) }) { products = it; showHome() }
    }))
    column.addView(heading("Favourites"))
    val favs = products.filter { favourites.contains(it.id) }
    if (favs.isEmpty()) column.addView(muted("Tap the heart on a product to keep it here."))
    else favs.forEach { column.addView(productRow(it)) }
    column.addView(heading("Menu"))
    products.forEach { column.addView(productRow(it)) }
    column.addView(rowOf(button("Cart (${cart.size})") { showCart() }, button("Sign out") {
      dispatch({ FoodlyApi.signOut() }) { user = null; cart = emptyList(); favourites.clear(); showSignIn() }
    }))
  }

  private fun productRow(product: FoodlyApi.Product): View {
    val label = TextView(this).apply {
      text = "${product.name}   ${formatPrice(product.price)}${if (product.inStock) "" else " · Sold out"}"
    }.grow()
    val isFav = favourites.contains(product.id)
    val heart = button(if (isFav) "♥" else "♡") {
      if (favourites.contains(product.id)) favourites.remove(product.id) else favourites.add(product.id)
      user?.let { Favourites.save(this, it.id, favourites) } // keep it on the device
      showHome()
    }.describe(if (isFav) "Remove ${product.name} from favourites" else "Add ${product.name} to favourites")
    val add = button("Add") {
      dispatch({ FoodlyApi.addToCart(product.id) }) { cart = it; toast("Added ${product.name}") }
    }.describe("Add ${product.name} to cart").also { it.isEnabled = product.inStock }
    return rowOf(label, heart, add)
  }

  private fun showCart() {
    column.removeAllViews()
    column.addView(header())
    column.addView(heading("Your cart"))
    if (cart.isEmpty()) {
      column.addView(muted("Your cart is empty."))
    } else {
      cart.forEach { item ->
        column.addView(rowOf(
          TextView(this).apply { text = "${item.name} × ${item.quantity}" }.grow(),
          TextView(this).apply { text = formatPrice(item.price * item.quantity) },
        ))
      }
    }
    column.addView(rowOf(TextView(this).apply { text = "Subtotal" }.grow(), TextView(this).apply { text = formatPrice(cart.subtotal()) }))
    column.addView(rowOf(button("Back") { showHome() }, button("Checkout") { showCheckout() }.also { it.isEnabled = cart.isNotEmpty() }))
  }

  private fun showCheckout() {
    column.removeAllViews()
    column.addView(header())
    column.addView(heading("Checkout"))
    val name = input("Full name", InputType.TYPE_CLASS_TEXT).also { it.setText(draft.optString("fullName")) }
    val phone = input("Phone", InputType.TYPE_CLASS_PHONE)
    val address = input("Address", InputType.TYPE_CLASS_TEXT)
    val promo = input("Promo code", InputType.TYPE_CLASS_TEXT)
    column.addView(name)
    column.addView(phone)
    column.addView(address)
    column.addView(rowOf(promo.grow(), button("Apply") {
      dispatch({ FoodlyApi.validatePromo(promo.text.toString().trim(), cart.subtotal()) }) { discount = it; refreshTotals() }
    }))
    totals = TextView(this)
    refreshTotals()
    column.addView(totals)
    column.addView(rowOf(button("Back") { showCart() }, button("Place order") {
      draft.put("fullName", name.text.toString()).put("phone", phone.text.toString()).put("address", address.text.toString())
      dispatch({ FoodlyApi.placeOrder(cart, draft, discount) }) { showConfirmed(it) }
    }))
  }

  private fun refreshTotals() {
    totals.text = "Total   ${formatPrice(cart.subtotal() - discount)}"
  }

  private fun showConfirmed(order: FoodlyApi.Order) {
    column.removeAllViews()
    column.addView(title("Order confirmed"))
    column.addView(TextView(this).apply { text = "Order ${order.id} · ${formatPrice(order.total)}" })
    column.addView(muted("Your food is on the way."))
    column.addView(button("Back to menu") { showHome() })
  }

  // ---- background dispatch ----

  private fun <T> dispatch(work: () -> T, onOk: (T) -> Unit) {
    io.execute {
      try {
        val result = work()
        runOnUiThread { onOk(result) }
      } catch (error: Throwable) {
        runOnUiThread { toast(error.message ?: "Something went wrong") }
      }
    }
  }

  // ---- view helpers ----

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private fun <T : View> T.grow(): T {
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    return this
  }

  private fun Button.describe(label: String): Button {
    contentDescription = label
    return this
  }

  private fun stacked(view: View): View {
    view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    return view
  }

  private fun title(text: String) = TextView(this).apply { this.text = text; textSize = 22f; setPadding(0, 0, 0, dp(12)) }
  private fun heading(text: String) = TextView(this).apply { this.text = text; textSize = 16f; setPadding(0, dp(16), 0, dp(6)) }
  private fun muted(text: String) = TextView(this).apply { this.text = text; setTextColor(0xFF8A7A70.toInt()) }

  private fun input(hint: String, type: Int): EditText = (EditText(this).apply {
    this.hint = hint
    inputType = type
    contentDescription = hint
  }).also { stacked(it) }

  private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    contentDescription = text
    setOnClickListener { onClick() }
  }

  private fun rowOf(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    views.forEach { addView(it) }
  }

  private fun header(): View = TextView(this).apply {
    text = "Foodly" + (user?.let { "    ${it.name} · ${it.loyaltyTier}" } ?: "")
    textSize = 18f
    setPadding(0, 0, 0, dp(12))
  }

  private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
