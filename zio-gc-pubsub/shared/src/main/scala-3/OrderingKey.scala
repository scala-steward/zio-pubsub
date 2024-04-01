package com.anymindgroup.pubsub.model

opaque type OrderingKey = String

object OrderingKey:
  def fromString(o: String): Option[OrderingKey] = Option(o).filter(_.nonEmpty)

  extension (x: OrderingKey) def value: String = x