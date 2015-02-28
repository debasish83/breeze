/*
 * Library of Proximal Algorithms adapted from https://github.com/cvxgrp/proximal
 * In-place modifications which later should be BLAS-ed when applicable for more efficiency
 * @author debasish83
 */

package breeze.optimize.proximal

import breeze.numerics.signum

import scala.math.max
import scala.math.min
import scala.math.sqrt
import scala.math.abs
import scala.Double.NegativeInfinity
import scala.Double.PositiveInfinity
import breeze.linalg._

trait Proximal {
  def prox(x: DenseVector[Double], rho: Double = 1.0)
  def valueAt(x: DenseVector[Double], rho: Double = 1.0) = 0.0
}

case class ProjectIdentity() extends Proximal {
  def prox(x: DenseVector[Double], rho: Double = 1.0) {}
}

//TO DO:
//1. Implement the binary search algorithm from http://see.stanford.edu/materials/lsocoee364b/hw4sol.pdf and compare performance
//2. Implement randomized algorithm from Duchi et al's paper Efficient Projections onto the l1-Ball for Learning in High Dimensions
case class ProjectProbabilitySimplex(s: Double) extends Proximal {
  require(s > 0, s"Proximal:ProjectProbabilitySimplex Radius s must be strictly positive")
  def prox(x: DenseVector[Double], rho: Double = 1.0)  = {
    val sorted = x.data.sorted(Ordering[Double].reverse)
    val cum = sorted.scanLeft(0.0)(_ + _).slice(1, x.length + 1)
    val cs = DenseVector(cum.zipWithIndex.map{elem => (elem._1 - s)/(elem._2 + 1)})
    val ndx = (DenseVector(sorted) - cs).data.filter{elem => elem >= 0.0}.length - 1
    var i = 0
    while(i < x.length) {
      x.update(i, max(x(i) - cs(ndx), 0.0))
      i = i + 1
    }
  }
}

/**
  * Adapted from TFOCS project (BSD license) https://github.com/cvxr/TFOCS/blob/master/proj_l1.m
  *
  * s      = sort(abs(nonzeros(x)),'descend');
  * cs     = cumsum(s);
  * ndx    = find( cs - (1:numel(s))' .* [ s(2:end) ; 0 ] >= q, 1 );
  * if ~isempty( ndx )
  *   thresh = ( cs(ndx) - q ) / ndx;
  * x = x .* ( 1 - thresh ./ max( abs(x), thresh ) ); % May divide very small numbers
  *
  */
case class ProjectL1TFOCS(s: Double) extends Proximal {
  def prox(x: DenseVector[Double], rho: Double = 1.0) = {
    val sorted = x.data.map {
      _.abs
    }.sorted(Ordering[Double].reverse)
    val cum = sorted.scanLeft(0.0)(_ + _).slice(1, x.length + 1)
    val cs = cum.zipWithIndex.map { elem =>
      val elemS = if (elem._2 == x.length - 1) 0.0 else sorted(elem._2 + 1)
      elem._1 - (elem._2 + 1) * elemS
    }
    val ndx = cs.indexWhere { case (elem) => elem >= s}
    if (ndx >= 0) {
      val thresh = (cum(ndx) - s) / (ndx + 1)
      var i = 0
      while (i < x.length) {
        x.update(i, x(i) * (1 - (thresh / max(abs(x(i)), thresh))))
        i = i + 1
      }
    }
  }
}

/**
 * Projection formula from Duchi et al's paper Efficient Projections onto the l1-Ball for Learning in High Dimensions
 * */
case class ProjectL1(s: Double) extends Proximal {
  val projectSimplex = ProjectProbabilitySimplex(s)

  def prox(x: DenseVector[Double], rho: Double = 1.0) = {
    val u = x.mapValues {_.abs}
    projectSimplex.prox(u, rho)
    var i = 0
    while (i < x.length) {
      x.update(i, signum(x(i)) * u(i))
      i = i + 1
    }
  }
}

case class ProjectBox(l: DenseVector[Double], u: DenseVector[Double]) extends Proximal {
  def prox(x: DenseVector[Double], rho: Double = 0.0) = {
    var i = 0
    while (i < x.length) {
      x.update(i, max(l(i), min(x(i), u(i))))
      i = i + 1
    }
  }
}

case class ProjectPos() extends Proximal {
  def prox(x: DenseVector[Double], rho: Double = 0.0) = {
    var i = 0
    while (i < x.length) {
      x.update(i, max(0, x(i)))
      i = i + 1
    }
  }
}

case class ProjectSoc() extends Proximal {
  def prox(x: DenseVector[Double], rho: Double = 0.0) = {
    var nx: Double = 0.0
    var i: Int = 1
    val n = x.length

    while (i < n) {
      nx += x(i) * x(i)
      i = i + 1
    }
    nx = sqrt(nx)

    if (nx > x(0)) {
      if (nx <= -x(0)) {
        i = 0
        while (i < n) {
          x(i) = 0
          i = i + 1
        }
      } else {
        val alpha = 0.5 * (1 + x(0) / nx)
        x.update(0, alpha * nx)
        i = 1
        while (i < n) {
          x.update(i, alpha * x(i))
          i = i + 1
        }
      }
    }
  }
}

//Projection onto Affine set
//Let C = { x \in R^{n} | Ax = b } where A \in R^{m x n}
//If A is full rank matrix then the projection is given by v - A'(Av - b) where A' is the cached Moore-Penrose pseudo-inverse of A
case class ProjectEquality(Aeq: DenseMatrix[Double], beq: DenseVector[Double]) extends Proximal {
  val invAeq = pinv(Aeq)
  def prox(x: DenseVector[Double], rho: Double = 0.0) = {
    val Av = Aeq*x
    Av -= beq
    x += invAeq*Av
  }
}

//Projection onto hyper-plane is a special case of projection onto affine set and is given by
//x + ((b - a'x)/||a||_2^2)a
case class ProjectHyperPlane(a: DenseVector[Double], b: Double) extends Proximal {
  val at = a.t

  def prox(x: DenseVector[Double], rho: Double = 0.0) = {
    val atx = at * x
    val anorm = norm(a, 2)
    val scale = (b - atx) / (anorm * anorm)
    val ascaled = a * scale
    x += ascaled
  }
}

case class ProximalL1() extends Proximal {
  var lambda = 1.0

  def setLambda(lambda: Double) = {
    this.lambda = lambda
    this
  }

  def prox(x: DenseVector[Double], rho: Double) = {
    var i = 0
    while (i < x.length) {
      x.update(i, max(0, x(i) - lambda/rho) - max(0, -x(i) - lambda/rho))
      i = i + 1
    }
  }

  override def valueAt(x: DenseVector[Double], rho: Double = 1.0) = {
    lambda * rho * x.foldLeft(0.0) { (agg, entry) => agg + abs(entry)}
  }
}

case class ProximalL2() extends Proximal {
  def prox(x: DenseVector[Double], rho: Double) = {
    var normSquare: Double = 0.0
    var i = 0

    while (i < x.length) {
      normSquare = normSquare + x(i) * x(i)
      i = i + 1
    }

    val norm = sqrt(normSquare)
    i = 0
    while (i < x.length) {
      if (norm >= 1 / rho) x.update(i, x(i) * (1 - 1 / (rho * norm)))
      else x.update(i, 0)
      i = i + 1
    }
  }
}

// f = (1/2)||.||_2^2
case class ProximalSumSquare() extends Proximal {
  def prox(x: DenseVector[Double], rho: Double) = {
    var i = 0
    while (i < x.length) {
      x.update(i, x(i) * (rho / (1 + rho)))
      i = i + 1
    }
  }
}

// f = -sum(log(x))
case class ProximalLogBarrier() extends Proximal {
  def prox(x: DenseVector[Double], rho: Double) = {
    var i = 0
    while (i < x.length) {
      x.update(i, 0.5 * (x(i) + sqrt(x(i) * x(i) + 4 / rho)))
      i = i + 1
    }
  }
}

// f = huber = x^2 if |x|<=1, 2|x| - 1 otherwise
case class ProximalHuber() extends Proximal {
  def proxScalar(v: Double, rho: Double, oracle: Double => Double, l: Double, u: Double, x0: Double): Double = {
    val MAX_ITER = 1000
    val tol = 1e-8

    var g: Double = 0.0
    var x = max(l, min(x0, u))

    var lIter = l
    var uIter = u
    var iter = 0

    while (iter < MAX_ITER && u - l > tol) {
      g = -1 / x + rho * (x - v)

      if (g > 0) {
        lIter = max(lIter, x - g / rho)
        uIter = x
      } else if (g < 0) {
        lIter = x
        uIter = min(uIter, x - g / rho)
      }
      x = (lIter + uIter) / 2
      iter = iter + 1
    }
    x
  }

  def proxSeparable(x: DenseVector[Double], rho: Double, oracle: Double => Double, l: Double, u: Double) = {
    x.map(proxScalar(_, rho, oracle, l, u, 0))

    var i = 0
    while (i < x.length) {
      x.update(i, proxScalar(x(i), rho, oracle, l, u, 0))
      i = i + 1
    }
  }

  def subgradHuber(x: Double): Double = {
    if (abs(x) <= 1) {
      2 * x
    } else {
      val projx = if (x > 0) x else -x
      2 * projx
    }
  }

  def prox(x: DenseVector[Double], rho: Double) = {
    proxSeparable(x, rho, subgradHuber, NegativeInfinity, PositiveInfinity)
  }
}

// f = c'*x
case class ProximalLinear(c: DenseVector[Double]) extends Proximal {
  def prox(x: DenseVector[Double], rho: Double) = {
    var i = 0
    while (i < x.length) {
      x.update(i, x(i) - c(i) / rho)
      i = i + 1
    }
  }
}

// f = c'*x + I(x >= 0)
case class ProximalLp(c: DenseVector[Double]) extends Proximal {
  def prox(x: DenseVector[Double], rho: Double) = {
    var i = 0
    while (i < x.length) {
      x.update(i, max(0, x(i) - c(i) / rho))
      i = i + 1
    }
  }
}
