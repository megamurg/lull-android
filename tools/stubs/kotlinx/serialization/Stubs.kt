// Compile-time stand-ins for kotlinx.serialization annotations, used only by tools/verify.sh, which cannot fetch the
// real runtime. The real build applies the serialization plugin and these annotations come from kotlinx-serialization-core.
package kotlinx.serialization

@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class Serializable

@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class SerialName(val value: String)

@Target(AnnotationTarget.PROPERTY)
annotation class Transient
