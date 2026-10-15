package com.nnomm.flashdeals.deals

import com.nnomm.flashdeals.auth.JWT_AUTH
import com.nnomm.flashdeals.auth.Role
import com.nnomm.flashdeals.auth.requireRole
import com.nnomm.flashdeals.db.Database
import com.nnomm.flashdeals.db.instant
import com.nnomm.flashdeals.db.queryOne
import com.nnomm.flashdeals.db.update
import com.nnomm.flashdeals.db.uuid
import com.nnomm.flashdeals.http.decodeBody
import com.nnomm.flashdeals.http.iso
import com.nnomm.flashdeals.http.notFound
import com.nnomm.flashdeals.http.parseInstant
import com.nnomm.flashdeals.http.parseUuidOrNull
import com.nnomm.flashdeals.http.StrictLongSerializer
import com.nnomm.flashdeals.http.receiveLimitedBytes
import com.nnomm.flashdeals.http.validation
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Serializable
data class CreateDealRequest(
    val title: String? = null,
    @SerialName("price_paise") @Serializable(with = StrictLongSerializer::class) val pricePaise: Long? = null,
    @SerialName("stock_total") @Serializable(with = StrictLongSerializer::class) val stockTotal: Long? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
)

@Serializable
data class DealResponse(
    val id: String,
    @SerialName("restaurant_id") val restaurantId: String,
    val title: String,
    @SerialName("price_paise") val pricePaise: Long,
    @SerialName("stock_total") val stockTotal: Int,
    @SerialName("stock_available") val stockAvailable: Int,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
)

data class NewDeal(
    val title: String,
    val pricePaise: Long,
    val stockTotal: Int,
    val startsAt: Instant,
    val endsAt: Instant,
)

fun CreateDealRequest.validate(): NewDeal {
    val title = title ?: validation("title is required")
    if (title.isBlank() || title.codePointCount(0, title.length) > 100) validation("title must be 1-100 characters")
    val price = pricePaise ?: validation("price_paise is required")
    if (price !in 1..1_000_000) validation("price_paise must be between 1 and 1000000")
    val stock = stockTotal ?: validation("stock_total is required")
    if (stock !in 1..10_000) validation("stock_total must be between 1 and 10000")
    val starts = parseInstant("starts_at", startsAt)
    val ends = parseInstant("ends_at", endsAt)
    if (!starts.isBefore(ends)) validation("starts_at must be before ends_at")
    return NewDeal(title, price, stock.toInt(), starts, ends)
}

object DealRepository {
    private const val COLUMNS =
        "id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at"

    fun insert(conn: Connection, restaurantId: UUID, deal: NewDeal): DealResponse {
        val id = UUID.randomUUID()
        conn.update(
            """
            INSERT INTO deals (id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id, restaurantId, deal.title, deal.pricePaise, deal.stockTotal, deal.stockTotal, deal.startsAt, deal.endsAt,
        )
        return find(conn, id)!!
    }

    fun find(conn: Connection, id: UUID): DealResponse? =
        conn.queryOne("SELECT $COLUMNS FROM deals WHERE id = ?", id, map = ::toDeal)

    fun toDeal(rs: ResultSet) = DealResponse(
        id = rs.uuid("id").toString(),
        restaurantId = rs.uuid("restaurant_id").toString(),
        title = rs.getString("title"),
        pricePaise = rs.getLong("price_paise"),
        stockTotal = rs.getInt("stock_total"),
        stockAvailable = rs.getInt("stock_available"),
        startsAt = rs.instant("starts_at").iso(),
        endsAt = rs.instant("ends_at").iso(),
    )
}

fun Route.dealRoutes(db: Database) {
    authenticate(JWT_AUTH) {
        route("/v1/deals") {
            post {
                val admin = call.requireRole(Role.RESTAURANT_ADMIN)
                val deal = decodeBody(CreateDealRequest.serializer(), call.receiveLimitedBytes()).validate()
                // Always the admin's own restaurant: restaurant_id is never read from the body.
                val created = db.tx { DealRepository.insert(it, admin.restaurantId!!, deal) }
                call.respond(HttpStatusCode.Created, created)
            }
            get("/{dealId}") {
                val id = parseUuidOrNull(call.parameters["dealId"]) ?: notFound("Deal not found")
                val deal = db.tx { DealRepository.find(it, id) } ?: notFound("Deal not found")
                call.respond(deal)
            }
        }
    }
}
