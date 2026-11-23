package com.bunch.recommendation.domain.combination

/**
 * How alike two photos look, from 0 (nothing alike) to 1 (the same image), or null when it cannot be told because a
 * photo has no value to compare (recommended bundle specification 5-4). The step that scores a card asks this and
 * compares the answer with a number of the settings, so they do not depend on what the number
 * is made of. The first version is made of the image fingerprint ([FingerprintSimilarity]); an embedding can take its
 * place without a change of the steps (4-2).
 */
fun interface ImageSimilarity {
    fun similarity(first: RecoPhoto, second: RecoPhoto): Double?
}

/**
 * The similarity of the 64-bit image fingerprints: the share of the bits that are the same. Two photos whose
 * fingerprints differ in 6 bits are 0.906 alike, in 3 bits 0.953. A photo without a fingerprint is not alike to
 * anything, not even to itself, because there is nothing to compare.
 */
object FingerprintSimilarity : ImageSimilarity {
    private const val BITS = Long.SIZE_BITS

    override fun similarity(first: RecoPhoto, second: RecoPhoto): Double? {
        val firstPrint = first.fingerprint ?: return null
        val secondPrint = second.fingerprint ?: return null
        return 1.0 - java.lang.Long.bitCount(firstPrint xor secondPrint).toDouble() / BITS
    }
}
