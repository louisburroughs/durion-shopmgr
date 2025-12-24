import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import java.sql.Timestamp
import java.time.Duration

/**
 * Shop Manager Services - Groovy implementation for complex business logic
 */

class DurShopMgrServices {

    /**
     * Assign mechanic to appointment with availability check
     */
    static Map assignMechanicToAppointment(ExecutionContext ec) {
        def appointmentId = ec.context.appointmentId
        def mechanicId = ec.context.mechanicId

        def entityFacade = ec.entity

        // Get appointment details
        def appointment = entityFacade.find("durion.shopmgr.DurShopAppointment")
            .condition("appointmentId", appointmentId)
            .one()

        if (!appointment) {
            ec.message.addError("Appointment ${appointmentId} not found")
            return [:]
        }

        // Get mechanic details
        def mechanic = entityFacade.find("durion.shopmgr.DurShopMechanic")
            .condition("mechanicId", mechanicId)
            .one()

        if (!mechanic) {
            ec.message.addError("Mechanic ${mechanicId} not found")
            return [:]
        }

        // Check mechanic status
        if (mechanic.statusId != "MECHANIC_ACTIVE") {
            ec.message.addError("Mechanic ${mechanic.firstName} ${mechanic.lastName} is not active")
            return [:]
        }

        // Check for conflicting appointments
        def conflictingAppts = entityFacade.find("durion.shopmgr.DurShopAppointment")
            .condition("mechanicId", mechanicId)
            .condition([
                entityCondition: ec.entity.conditionFactory.makeCondition([
                    ec.entity.conditionFactory.makeCondition("appointmentDate", "<=", appointment.scheduledEndTime ?: appointment.appointmentDate),
                    ec.entity.conditionFactory.makeCondition("scheduledEndTime", ">=", appointment.scheduledStartTime ?: appointment.appointmentDate)
                ], "and")
            ])
            .condition("statusId", "in", ["APPT_SCHEDULED", "APPT_CONFIRMED", "APPT_IN_PROGRESS"])
            .condition("appointmentId", "!=", appointmentId)
            .count()

        if (conflictingAppts > 0) {
            ec.message.addError("Mechanic has conflicting appointment(s) during this time")
            return [:]
        }

        // Assign mechanic
        appointment.mechanicId = mechanicId
        appointment.update()

        ec.message.addMessage("Mechanic assigned successfully")
        return [:]
    }

    /**
     * Assign location to appointment with availability check
     */
    static Map assignLocationToAppointment(ExecutionContext ec) {
        def appointmentId = ec.context.appointmentId
        def locationId = ec.context.locationId

        def entityFacade = ec.entity

        // Get appointment details
        def appointment = entityFacade.find("durion.shopmgr.DurShopAppointment")
            .condition("appointmentId", appointmentId)
            .one()

        if (!appointment) {
            ec.message.addError("Appointment ${appointmentId} not found")
            return [:]
        }

        // Get location details
        def location = entityFacade.find("durion.shopmgr.DurShopLocation")
            .condition("locationId", locationId)
            .one()

        if (!location) {
            ec.message.addError("Location ${locationId} not found")
            return [:]
        }

        // Check location status
        if (location.statusId == "LOCATION_OUT_OF_SERVICE") {
            ec.message.addError("Location ${location.locationName} is out of service")
            return [:]
        }

        // Check capacity
        def appointmentsAtLocation = entityFacade.find("durion.shopmgr.DurShopAppointment")
            .condition("locationId", locationId)
            .condition([
                entityCondition: ec.entity.conditionFactory.makeCondition([
                    ec.entity.conditionFactory.makeCondition("appointmentDate", "<=", appointment.scheduledEndTime ?: appointment.appointmentDate),
                    ec.entity.conditionFactory.makeCondition("scheduledEndTime", ">=", appointment.scheduledStartTime ?: appointment.appointmentDate)
                ], "and")
            ])
            .condition("statusId", "in", ["APPT_SCHEDULED", "APPT_CONFIRMED", "APPT_IN_PROGRESS"])
            .condition("appointmentId", "!=", appointmentId)
            .count()

        if (appointmentsAtLocation >= (location.capacity ?: 1)) {
            ec.message.addError("Location ${location.locationName} is at capacity during this time")
            return [:]
        }

        // Assign location
        appointment.locationId = locationId
        appointment.update()

        ec.message.addMessage("Location assigned successfully")
        return [:]
    }

    /**
     * Get mechanic availability for date range
     */
    static Map getMechanicAvailability(ExecutionContext ec) {
        def mechanicId = ec.context.mechanicId
        def fromDate = ec.context.fromDate
        def thruDate = ec.context.thruDate

        def entityFacade = ec.entity

        // Get mechanic
        def mechanic = entityFacade.find("durion.shopmgr.DurShopMechanic")
            .condition("mechanicId", mechanicId)
            .one()

        if (!mechanic || mechanic.statusId != "MECHANIC_ACTIVE") {
            return [availableSlots: []]
        }

        // Get existing appointments for mechanic in date range
        def appointments = entityFacade.find("durion.shopmgr.DurShopAppointment")
            .condition("mechanicId", mechanicId)
            .condition("appointmentDate", ">=", fromDate)
            .condition("appointmentDate", "<=", thruDate)
            .condition("statusId", "in", ["APPT_SCHEDULED", "APPT_CONFIRMED", "APPT_IN_PROGRESS"])
            .orderBy("appointmentDate")
            .list()

        // Generate available slots (simplified - assumes 8am-5pm workday)
        def availableSlots = []
        def currentDate = fromDate.toLocalDate()
        def endDate = thruDate.toLocalDate()

        while (!currentDate.isAfter(endDate)) {
            // Skip weekends (simplified)
            if (currentDate.dayOfWeek.value < 6) {
                def dayStart = currentDate.atTime(8, 0)
                def dayEnd = currentDate.atTime(17, 0)

                // Check for appointments this day
                def dayAppointments = appointments.findAll {
                    it.appointmentDate.toLocalDateTime().toLocalDate() == currentDate
                }

                if (dayAppointments.isEmpty()) {
                    availableSlots.add([
                        date: currentDate,
                        startTime: dayStart,
                        endTime: dayEnd,
                        available: true
                    ])
                } else {
                    // Could implement more granular slot detection here
                    availableSlots.add([
                        date: currentDate,
                        startTime: dayStart,
                        endTime: dayEnd,
                        available: false,
                        appointments: dayAppointments.size()
                    ])
                }
            }

            currentDate = currentDate.plusDays(1)
        }

        return [availableSlots: availableSlots]
    }

    /**
     * Calculate work log hours from start/end times
     */
    static Map calculateWorkLogHours(ExecutionContext ec) {
        def workLogId = ec.context.workLogId

        def entityFacade = ec.entity

        def workLog = entityFacade.find("durion.shopmgr.DurShopWorkLog")
            .condition("workLogId", workLogId)
            .one()

        if (!workLog) {
            ec.message.addError("Work log ${workLogId} not found")
            return [hoursWorked: BigDecimal.ZERO]
        }

        if (!workLog.startTime || !workLog.endTime) {
            ec.message.addError("Work log must have both start and end times")
            return [hoursWorked: BigDecimal.ZERO]
        }

        // Calculate duration in hours
        def startTime = workLog.startTime.toInstant()
        def endTime = workLog.endTime.toInstant()
        def duration = Duration.between(startTime, endTime)
        def hoursWorked = new BigDecimal(duration.toMinutes()).divide(new BigDecimal(60), 2, BigDecimal.ROUND_HALF_UP)

        // Update work log
        workLog.hoursWorked = hoursWorked
        if (!workLog.billableHours) {
            workLog.billableHours = hoursWorked
        }
        workLog.update()

        return [hoursWorked: hoursWorked]
    }
}
