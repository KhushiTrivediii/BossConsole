package ai.rever.boss.components.model

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/** A pointer handler owns its drag even when cancellation bypasses the gesture callbacks. */
internal suspend fun PointerInputScope.detectTabDragGestures(
    component: TabDraggableComponent,
    onStart: (Offset) -> Unit,
    onEnd: (TabDropResult?) -> Unit,
    sourceIndex: () -> Int? = { null },
) {
    component.withDragSession { session ->
        try {
            detectDragGestures(
                onDragStart = { offset -> session.start { onStart(offset) } },
                onDrag = { change, amount ->
                    if (session.isOwner) {
                        change.consume()
                        session.update(amount)
                    }
                },
                onDragEnd = {
                    if (session.isOwner) onEnd(session.end(sourceIndex()))
                },
                onDragCancel = {
                    if (session.cancel()) onEnd(null)
                },
            )
        } finally {
            // Teardown and callback failures terminate only this gesture and notify once.
            if (session.cancel()) onEnd(null)
        }
    }
}
