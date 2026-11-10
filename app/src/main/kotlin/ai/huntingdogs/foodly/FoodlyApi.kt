package ai.huntingdogs.foodly

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The Foodly backend client — same contract as examples/foodly-backend/server.mjs. Blocking calls,
 *  meant to run off the main thread (MainActivity dispatches them on a background executor). From the
 *  emulator the host is 10.0.2.2; override [base] to point elsewhere. */
object FoodlyApi {
  @Volatile var base: String = "https://huntingdogs-foodly-api-snbl7sdvbq-ew.a.run.app"
  @Volatile private var token: String? = null

  data class Product(val id: String, val name: String, val price: Int, val inStock: Boolean, val category: String)
  data class User(val id: String, val name: String, val email: String, val loyaltyTier: String)
  data class CartItem(val productId: String, val name: String, val price: Int, val quantity: Int)
  data class Order(val id: String, val total: Int)

  fun signIn(email: String, password: String): User {
    val body = JSONObject().put("email", email).put("password", password)
    val result = request("POST", "/auth/sign-in", body, auth = false)
    token = result.getString("accessToken")
    val user = result.getJSONObject("user")
    return User(user.getString("id"), user.getString("name"), user.getString("email"), user.optString("loyaltyTier", "Gold"))
  }

  fun signOut() {
    runCatching { request("POST", "/auth/sign-out", null, auth = true) }
    token = null
  }

  fun searchProducts(query: String = ""): List<Product> {
    val result = request("GET", "/products/search?q=" + URLEncoder.encode(query, "UTF-8"), null, auth = false)
    val array = result.getJSONArray("products")
    return (0 until array.length()).map { index ->
      val p = array.getJSONObject(index)
      Product(p.getString("id"), p.getString("name"), p.getInt("price"), p.getBoolean("inStock"), p.optString("category", ""))
    }
  }

  fun addToCart(productId: String, quantity: Int = 1): List<CartItem> {
    val body = JSONObject().put("productId", productId).put("quantity", quantity)
    val result = request("POST", "/cart/items", body, auth = true)
    val array = result.getJSONArray("items")
    return (0 until array.length()).map { index ->
      val i = array.getJSONObject(index)
      CartItem(i.getString("productId"), i.getString("name"), i.getInt("price"), i.getInt("quantity"))
    }
  }

  fun validatePromo(code: String, subtotal: Int): Int {
    val body = JSONObject().put("code", code).put("subtotal", subtotal)
    val result = runCatching { request("POST", "/promotions/validate", body, auth = true) }.getOrNull()
    return result?.optInt("discount", 0) ?: 0
  }

  fun placeOrder(cart: List<CartItem>, draft: JSONObject, discount: Int): Order {
    val items = JSONArray()
    for (item in cart) {
      items.put(JSONObject().put("productId", item.productId).put("name", item.name).put("price", item.price).put("quantity", item.quantity))
    }
    val body = JSONObject().put("items", items).put("draft", draft).put("discount", discount)
    val result = request("POST", "/orders", body, auth = true)
    return Order(result.getString("id"), result.getInt("total"))
  }

  private fun request(method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject {
    val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = 10_000
      readTimeout = 10_000
      if (auth) token?.let { setRequestProperty("Authorization", "Bearer $it") }
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
      }
    }
    try {
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) {
        val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
        throw RuntimeException(if (message.isNullOrEmpty()) "Request failed ($code)" else message)
      }
      return if (text.isEmpty()) JSONObject() else JSONObject(text)
    } finally {
      connection.disconnect()
    }
  }
}

fun List<FoodlyApi.CartItem>.subtotal(): Int = sumOf { it.price * it.quantity }

fun formatPrice(cents: Int): String = "$" + String.format("%.2f", cents / 100.0)
