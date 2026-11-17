package com.bunch.recommendation.domain.combination

/**
 * The numbers that stand for how a photo looks, so that two photos can be told alike by their cosine similarity
 * (recommended bundle specification 4-1 F10 and 4-2). The vector is normalized to length 1 when it is made, so the
 * similarity of two of them is the sum of the products of their numbers. Two embeddings are equal when all their
 * numbers are, which a plain float array does not do.
 */
class Embedding(val values: FloatArray) {
    init {
        require(values.isNotEmpty()) {
            "an embedding has at least one number"
        }
    }

    override fun equals(other: Any?): Boolean = other is Embedding && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()
}
