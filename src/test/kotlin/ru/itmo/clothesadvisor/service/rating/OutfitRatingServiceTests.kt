package ru.itmo.clothesadvisor.service.rating

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import ru.itmo.clothesadvisor.dto.rating.CreateRatingRequest
import ru.itmo.clothesadvisor.dto.rating.RatingResponse
import ru.itmo.clothesadvisor.dto.rating.UpdateRatingRequest
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.outfit.Outfit
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.rating.OutfitRating
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistory
import ru.itmo.clothesadvisor.model.rating.OutfitRatingId
import ru.itmo.clothesadvisor.model.rating.RatingVote
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.rating.OutfitRatingHistoryRepository
import ru.itmo.clothesadvisor.repository.rating.OutfitRatingRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService

class OutfitRatingServiceTests {
    private val outfits = mock(OutfitRepository::class.java)
    private val access = mock(AccessGrantService::class.java)
    private val ratings = mock(OutfitRatingRepository::class.java)
    private val history = mock(OutfitRatingHistoryRepository::class.java)
    private val service = OutfitRatingService(outfits, access, ratings, history)
    private val storedAt = Instant.parse("2026-01-01T00:00:00Z")
    private val key = OutfitRatingId(11, 2)

    private fun outfit(author: Long = 1) =
        Outfit(1, author, OutfitSource.USER, "Daily", storedAt).apply { id = 11 }

    private fun allowRating() {
        `when`(outfits.findLockedByIdAndOwnerId(11, 1)).thenReturn(outfit())
    }

    @Test
    fun `duplicate creation uses insert conflict result without rating pre read`() {

        // given
        allowRating()
        `when`(history.maxVersion(11, 2)).thenReturn(3)

        // when
        val failure = catchThrowable {
            service.create(2, 1, 11, CreateRatingRequest(RatingVote.LIKE))
        }

        // then
        assertThat(failure).isInstanceOf(RatingConflictException::class.java)
        verify(ratings).insert(key, RatingVote.LIKE, 4)
        verify(ratings, never()).findById(key)
        verify(access, times(2)).requireAccess(2, 1)
    }

    @Test
    fun `recreated rating continues the historical version sequence`() {

        // given
        allowRating()
        `when`(history.maxVersion(11, 2)).thenReturn(4)
        `when`(ratings.insert(key, RatingVote.DISLIKE, 5))
            .thenReturn(OutfitRating(key, RatingVote.DISLIKE, storedAt, version = 5))

        // when
        val response = service.create(2, 1, 11, CreateRatingRequest(RatingVote.DISLIKE))

        // then
        assertThat(response).isEqualTo(RatingResponse(RatingVote.DISLIKE, 5))
    }

    @Test
    fun `author and revoked access after lock cannot write a rating`() {

        // given
        `when`(outfits.findLockedByIdAndOwnerId(11, 1)).thenReturn(outfit(2))

        // when
        val selfRating = catchThrowable {
            service.create(2, 1, 11, CreateRatingRequest(RatingVote.LIKE))
        }

        // then
        assertThat(selfRating).isInstanceOf(AccessDeniedException::class.java)

        // given
        allowRating()
        doNothing().doThrow(EntityNotFoundException()).`when`(access).requireAccess(2, 1)

        // when
        val revokedAccess = catchThrowable {
            service.create(2, 1, 11, CreateRatingRequest(RatingVote.LIKE))
        }

        // then
        assertThat(revokedAccess).isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(ratings, history)
    }

    @Test
    fun `identical vote still advances once and archives exact previous state`() {

        // given
        allowRating()
        val original = OutfitRating(key, RatingVote.LIKE, storedAt, version = 3)
        val saved = OutfitRating(key, RatingVote.LIKE, storedAt, storedAt.plusSeconds(2), 4)
        `when`(ratings.findById(key)).thenReturn(original)
        `when`(ratings.update(original, 3)).thenReturn(saved)
        var archived: OutfitRatingHistory? = null
        doAnswer {
                archived = it.getArgument(0)
                null
            }
            .`when`(history)
            .insert(any(OutfitRatingHistory::class.java) ?: OutfitRatingHistory(original))

        // when
        val response = service.update(2, 1, 11, UpdateRatingRequest(RatingVote.LIKE, 3))

        // then
        assertThat(response).isEqualTo(RatingResponse(RatingVote.LIKE, 4))
        assertThat(archived!!.id.version).isEqualTo(3)
        assertThat(archived!!.modifiedAt).isEqualTo(storedAt)
        assertThat(archived!!.vote).isEqualTo(RatingVote.LIKE)
    }

    @Test
    fun `stale withdrawal leaves current and history untouched`() {

        // given
        allowRating()
        val original = OutfitRating(key, RatingVote.LIKE, storedAt, version = 3)
        `when`(ratings.findById(key)).thenReturn(original)

        // when
        val failure = catchThrowable { service.withdraw(2, 1, 11, 2) }

        // then
        assertThat(failure).isInstanceOf(RatingConflictException::class.java)
        verify(ratings, never()).delete(original)
        verifyNoInteractions(history)
    }
}
