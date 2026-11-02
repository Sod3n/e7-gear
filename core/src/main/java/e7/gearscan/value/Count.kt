package e7.gearscan.value

@JvmInline
value class Count(
    val n: Int,
) {
    fun of(
        singular: String,
        plural: String = singular + "s",
    ) = "$n ${if (n == 1) singular else plural}"
}
