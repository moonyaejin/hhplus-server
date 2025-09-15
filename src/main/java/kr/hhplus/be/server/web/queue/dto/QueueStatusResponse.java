package kr.hhplus.be.server.web.queue.dto;

record QueueStatusResponse(
        Long activeUsers,
        Long availableSlots,
        Long waitingUsers,
        Long estimatedWaitMinutes
) {}