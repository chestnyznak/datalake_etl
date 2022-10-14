package ru.crpt.analytics.datalake.base.utils

import java.sql.Date
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DatesHelper {
  private val dateFormat: String = "yyyy-MM-dd"
  private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(dateFormat)

  def validateDate(date: String): LocalDate = {
    //Throws exception if date is incorrect
    LocalDate.parse(date, this.dateFormatter)
  }
  def validateStartEnd(dateStart: String, dateEnd: String, checkStartIsBeforeEnd: Boolean = true): (LocalDate, LocalDate) = {
    val dStart = validateDate(dateStart)
    val dEnd = validateDate(dateEnd)
    if(checkStartIsBeforeEnd && dEnd.isBefore(dStart)) throw new RuntimeException(s"Start date [$dateStart] is later than end date [$dateEnd]")
    (dStart, dEnd)
  }

  def getContiniousDatesSequence(dateStart: LocalDate, dateEnd: LocalDate): Seq[String] = {
    val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(dateStart, dateEnd)
    (0 to daysBetween.toInt).map(x => dateStart.plusDays(x).toString)
  }

  object DateOrdering extends Ordering[Date] {
    override def compare(x: Date, y: Date): Int = x.compareTo(y)
  }
}
