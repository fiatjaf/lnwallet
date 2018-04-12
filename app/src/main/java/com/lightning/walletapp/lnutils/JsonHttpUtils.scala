package com.lightning.walletapp.lnutils

import spray.json._
import scala.concurrent.duration._
import com.lightning.walletapp.ln._
import com.lightning.walletapp.Denomination._
import com.lightning.walletapp.lnutils.JsonHttpUtils._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import rx.lang.scala.{Observable => Obs}

import com.lightning.walletapp.lnutils.olympus.OlympusWrap.Fiat2Btc
import com.lightning.walletapp.lnutils.olympus.OlympusWrap
import org.bitcoinj.core.Transaction.DEFAULT_TX_FEE
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.walletapp.AbstractKit
import com.lightning.walletapp.Utils.app
import org.bitcoinj.core.Coin
import spray.json.JsonFormat
import scala.util.Try


object JsonHttpUtils {
  def initDelay[T](next: Obs[T], startMillis: Long, timeoutMillis: Long) = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 0L) 0L else adjustedTimeout
    Obs.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def obsOnIO = Obs just null subscribeOn IOScheduler.apply
  def repeat[T](obs: Obs[T], pick: (Unit, Int) => Duration, times: Range) =
    obs.repeatWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)

  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range) =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)

  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  def pickInc(errorOrUnit: Any, next: Int) = next.seconds
}

object RatesSaver {
  def safe = retry(OlympusWrap.getRates, pickInc, 3 to 4)
  def initialize = initDelay(safe, rates.stamp, 1800000) foreach { case newFee \ newFiat =>
    val sensibleFees = for (notZero <- newFee("6") +: rates.feeHistory if notZero > 0) yield notZero
    rates = Rates(feeHistory = sensibleFees take 3, exchange = newFiat, stamp = System.currentTimeMillis)
    app.prefs.edit.putString(AbstractKit.RATES_DATA, rates.toJson.toString).commit
  }

  var rates = {
    val raw = app.prefs.getString(AbstractKit.RATES_DATA, new String)
    Try(raw) map to[Rates] getOrElse Rates(Nil, Map.empty, 0)
  }
}

case class Rates(feeHistory: Seq[Double], exchange: Fiat2Btc, stamp: Long) {
  // Testnet was not accepting transactions with too low fee so use some hard min

  private[this] val minAllowedFee = Coin valueOf 5000L
  val feeLive = if (feeHistory.isEmpty) DEFAULT_TX_FEE else {
    val averageFee: Coin = btcBigDecimal2MSat(feeHistory.sum / feeHistory.size)
    if (averageFee isLessThan minAllowedFee) minAllowedFee else averageFee
  }
}