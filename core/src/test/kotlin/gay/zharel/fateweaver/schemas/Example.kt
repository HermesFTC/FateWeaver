package gay.zharel.fateweaver.schemas

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Vector2d(val x: Double, val y: Double)
data class Rotation2d(val real: Double, val imaginary: Double) {
    fun toDouble() = atan2(imaginary, real)

    companion object {
        fun fromDouble(value: Double) = Rotation2d(cos(value), sin(value))
    }
}

data class Pose2d(val position: Vector2d, val heading: Rotation2d)

class PoseMessage(pose: Pose2d) {
    val timestamp = System.nanoTime()
    val x = pose.position.x
    val y = pose.position.y
    val heading = pose.heading.toDouble()
}

val schema: FateSchema<Pose2d> = TranslatedSchema(FateSchema.schemaOfClass(PoseMessage::class), ::PoseMessage)

/**
 * (Int) -> Int
 * UnaryOperator<Integer> myFunction = (x) -> x * x;
 * UnaryOperator<Integer> myFunction = x -> {
 *      return x * x;
 * }
 */

val myFunction: (Int) -> Int = { x -> x * x }
val myFunction2: (Int) -> Int = { it * it }