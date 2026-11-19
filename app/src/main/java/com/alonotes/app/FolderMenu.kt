package com.alonotes.app

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

/**
 * Menú de toque largo de una carpeta (como FolderIconMenu de AloTube): Establecer icono,
 * Quitar fondo (solo si ya tiene imagen) y Archivar/Desarchivar. Nunca toca archivos del usuario.
 */
internal object FolderMenu {
    fun show(a: Activity, f: NoteStore.Item, pickIcon: () -> Unit, changed: () -> Unit) {
        val l = UiKit.vbox(a, 18)
        val title = UiKit.text(a, f.name, 17, a.getColor(R.color.ink))
        title.setSingleLine(true)
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(UiKit.dp(a, 4), 0, 0, UiKit.dp(a, 6))
        l.addView(title)
        val ref = DlgRef()
        fun opt(label: String, color: Int, run: () -> Unit) {
            val t: TextView = UiKit.text(a, label, 17, color)
            t.setPadding(UiKit.dp(a, 4), UiKit.dp(a, 12), UiKit.dp(a, 4), UiKit.dp(a, 12))
            t.setOnClickListener {
                ref.dismiss()
                run()
            }
            l.addView(t, ViewGroup.LayoutParams(-1, -2))
        }
        val ink = a.getColor(R.color.ink)
        opt("Establecer icono", ink) { pickIcon() }
        if (FolderIconStore.get(a, f.id) != null) {
            opt("Quitar fondo", ink) {
                FolderIconStore.remove(a, f.id)
                Toast.makeText(a, "Quitado", Toast.LENGTH_SHORT).show()
                changed()
            }
        }
        val arch = ArchiveStore.isArchived(a, f.id)
        opt(if (arch) "Desarchivar" else "Archivar", ink) {
            ArchiveStore.set(a, f.id, !arch)
            changed()
        }
        ref.d = UiKit.show(a, l, false)
    }

    /** Guarda la imagen elegida como ícono (en segundo plano) y avisa. */
    fun applyIcon(a: Activity, folderId: String, uri: Uri, done: () -> Unit) {
        Thread {
            val ok = FolderIconStore.set(a, folderId, uri)
            a.runOnUiThread {
                Toast.makeText(a, if (ok) "Aplicado" else "No se pudo aplicar", Toast.LENGTH_SHORT).show()
                done()
            }
        }.start()
    }
}
