package com.bunch.global.job

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.transaction.PlatformTransactionManager

class AsyncJobSchedulingTest {
    @Test
    fun `disabled async worker stays idle when profanity independently enables scheduling`() {
        val jobs = mock(AsyncJobRepository::class.java)
        val transactions = mock(PlatformTransactionManager::class.java)
        val worker = AsyncJobWorker(jobs, emptyList(), transactions, 10, 300, 5, 10, enabled = false)

        worker.poll()

        verifyNoInteractions(jobs, transactions)
    }
}
