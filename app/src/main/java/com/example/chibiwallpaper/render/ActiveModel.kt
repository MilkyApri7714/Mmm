package com.example.chibiwallpaper.render

/**
 * PHẦN 15.3 — Tách ra khỏi [MultiModelScene] thành file riêng để [PortalTransitionOverlay]/
 * [FullPortalOverlay] hết phụ thuộc [MultiModelScene], và [com.example.chibiwallpaper.floating.
 * FloatingPetRenderer] (khác package) dùng chung được cùng 1 enum thay vì phải tự định nghĩa lại.
 * [MultiModelScene] vẫn import lại như cũ (cùng package `render` nên không cần khai `import` gì
 * thêm) — không đổi hành vi wallpaper.
 */
enum class ActiveModel { SLIME, CHIBI, FULL, VIDEO }
