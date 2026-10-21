package com.hanyang.backend.domain.model3d.outbox

import com.hanyang.backend.domain.model3d.*
import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.property.PropertyRepository
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.util.UUID

/** Real database transactions and persisted leases; the remote AI acknowledgement is controlled. */
@SpringBootTest
class OutboxCorrectionRecoveryIntegrationTest @Autowired constructor(
    private val users: UserRepository, private val properties: PropertyRepository, private val rooms: RoomRepository,
    private val models: Room3DModelRepository, private val pipeline: Room3DModelService,
    private val dispatch: OutboxDispatchService, private val json: JsonMapper
) {
    @field:MockitoBean private lateinit var ai: AiServerClient
    @field:MockitoSpyBean private lateinit var outbox: OutboxEventRepository
    private val correction = CorrectionRequestDto(listOf(VertexDto("aaaaaaaa-0000-0000-0000-000000000001",10.0,20.0,0.0,imageIndex=0)),emptyList())

    @Test fun `correction transition commits with a persisted immutable command before any HTTP delivery`() {
        val model=fixture()
        pipeline.applyCorrectionAndReconstruct(requireNotNull(model.room.id),correction,requireNotNull(model.id))
        val command=command(model)
        assertEquals(ModelStatus.RECONSTRUCTING,models.findById(requireNotNull(model.id)).orElseThrow().status)
        assertEquals(OutboxStatus.PENDING,command.status)
        assertEquals(OutboxEventType.AI_CORRECTION_RECONSTRUCT,command.eventType)
        val payload=json.readValue(command.payload,AiCorrectionReconstructRequestDto::class.java)
        assertEquals(model.aiRoomId,payload.aiRoomId);assertEquals(correction,payload.correction)
        Mockito.verifyNoInteractions(ai)
    }
    @Test fun `outbox insert failure rolls back the reconstructing model transition`() {
        val model=fixture()
        Mockito.doThrow(IllegalStateException("forced command insert failure")).`when`(outbox).save(Mockito.any(OutboxEvent::class.java))
        assertThrows(IllegalStateException::class.java) {
            pipeline.applyCorrectionAndReconstruct(requireNotNull(model.room.id),correction,requireNotNull(model.id))
        }
        assertEquals(ModelStatus.WAITING_FOR_CORRECTION,models.findById(requireNotNull(model.id)).orElseThrow().status)
        Mockito.verifyNoInteractions(ai)
    }
    @Test fun `lost database acknowledgement is replayed after lease expiry using the same correction and idempotency key`() {
        val model=fixture();pipeline.applyCorrectionAndReconstruct(requireNotNull(model.room.id),correction,requireNotNull(model.id))
        val persisted=command(model);val id=requireNotNull(persisted.id)
        val original=dispatch.claimBatch(100).first { it.id==id }
        Mockito.`when`(ai.sendCorrection(model.aiRoomId,correction,"${original.idempotencyKey}:correction")).thenReturn(Mono.empty())
        Mockito.`when`(ai.reconstruct(model.aiRoomId,"${original.idempotencyKey}:reconstruct")).thenReturn(Mono.empty())
        dispatch.send(original).block()
        Mockito.doThrow(IllegalStateException("simulated lost database ACK")).`when`(outbox).markSentIfOwned(
            Mockito.eq(original.id),Mockito.eq(original.lockToken) ?: "",Mockito.eq(OutboxStatus.PROCESSING) ?: OutboxStatus.PROCESSING,Mockito.eq(OutboxStatus.SENT) ?: OutboxStatus.SENT,Mockito.any(LocalDateTime::class.java) ?: LocalDateTime.now()
        )
        assertThrows(IllegalStateException::class.java) { dispatch.markSent(original) }
        val abandoned=outbox.findById(id).orElseThrow()
        assertEquals(OutboxStatus.PROCESSING,abandoned.status)
        abandoned.lockedUntil=LocalDateTime.now().minusSeconds(1);outbox.saveAndFlush(abandoned)
        val recovered=dispatch.claimBatch(100).first { it.id==id }
        assertNotEquals(original.lockToken,recovered.lockToken)
        assertEquals(original.idempotencyKey,recovered.idempotencyKey)
        assertEquals(original.payload,recovered.payload)
        assertEquals(OutboxEventType.AI_CORRECTION_RECONSTRUCT,recovered.eventType)
        assertEquals(2,recovered.attemptCount)
        dispatch.send(recovered).block()
        // Remove the controlled acknowledgement failure; the replacement owner can now commit SENT.
        Mockito.reset(outbox)
        assertTrue(dispatch.markSent(recovered))
        assertEquals(OutboxStatus.SENT,outbox.findById(id).orElseThrow().status)
        Mockito.verify(ai,Mockito.times(2)).sendCorrection(model.aiRoomId,correction,"${original.idempotencyKey}:correction")
        Mockito.verify(ai,Mockito.times(2)).reconstruct(model.aiRoomId,"${original.idempotencyKey}:reconstruct")
    }
    private fun command(model:Room3DModel)=outbox.findAll().single { it.aggregateId==model.aiRoomId && it.eventType==OutboxEventType.AI_CORRECTION_RECONSTRUCT }
    private fun fixture():Room3DModel {
        val suffix=UUID.randomUUID().toString()
        val owner=users.saveAndFlush(User(email="durable-$suffix@example.com",name="Owner",provider="TEST",providerId=suffix,role="HOST"))
        val property=properties.saveAndFlush(Property(title="Durable building",address="Seoul",creatorUserId=owner.id))
        val room=rooms.saveAndFlush(Room(property=property,owner=owner,roomName="Durable room",pricePerNight=1000,maxGuests=1))
        return models.saveAndFlush(Room3DModel(room=room,status=ModelStatus.WAITING_FOR_CORRECTION))
    }
}
