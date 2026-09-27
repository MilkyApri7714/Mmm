package com.example.chibiwallpaper.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * PHẦN 12 — Tra số điện thoại theo tên trong Danh bạ, dùng cho "gọi cho Mẹ" / "nhắn Long là
 * mình trễ 10 phút" (xem [ActionRouter]).
 *
 * Quyền READ_CONTACTS đã khai báo sẵn trong AndroidManifest — chỉ cần user cấp runtime
 * (nút "Cấp quyền Danh bạ" ở MainActivity, Section "Tính năng mở rộng").
 * Nếu chưa được cấp, [findByName] trả về null thay vì crash — [ActionRouter] tự trả lời
 * phù hợp cho Milky nói.
 */
object ContactsHelper {

    data class Contact(val displayName: String, val phoneNumber: String)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Tìm số điện thoại theo tên (khớp gần đúng — LIKE %name%, không cần đúng dấu/hoa thường
     * tuyệt đối vì SQLite LIKE trên Android không phân biệt hoa/thường với ASCII).
     * @return contact đầu tiên khớp, hoặc null nếu chưa có quyền / không tìm thấy / tên rỗng.
     */
    fun findByName(context: Context, name: String): Contact? {
        if (!hasPermission(context) || name.isBlank()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%${name.trim()}%")

        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0) ?: name
                    val rawNumber = cursor.getString(1) ?: return null
                    Contact(displayName, rawNumber.replace(" ", "").replace("-", ""))
                } else {
                    null
                }
            }
        } catch (e: SecurityException) {
            // Quyền có thể bị thu hồi giữa lúc check và query — bỏ qua, trả về null.
            null
        }
    }
}
