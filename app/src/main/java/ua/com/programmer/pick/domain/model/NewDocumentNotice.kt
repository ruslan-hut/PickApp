package ua.com.programmer.pick.domain.model

/** A document that just entered the worker's queue, as the server reported it. */
data class NewDocumentNotice(
    val id: String,
    val number: String,
    val salesChannel: String?,
)
