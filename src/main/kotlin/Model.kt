import javafx.animation.SequentialTransition
import javafx.animation.Timeline
import javafx.beans.binding.Bindings
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import tornadofx.*
import java.util.concurrent.Callable


val sequentialTransition = SequentialTransition()
operator fun SequentialTransition.plusAssign(timeline: Timeline) { children += timeline }

var speed = 200.millis

data class Point(val x: Double, val y: Double)



fun ccw(a: Point, b: Point, c: Point) =
        (c.y - a.y) * (b.x - a.x) > (b.y - a.y) * (c.x - a.x)

fun intersect(a: Point, b: Point, c: Point, d: Point) =
        ccw(a,c,d) != ccw(b,c,d) && ccw(a,b,c) != ccw(a,b,d)


class Edge(city: City) {

    val startCityProperty = SimpleObjectProperty(city)
    var startCity by startCityProperty
    val startPoint get() = startCity.let { Point(it.x,it.y) }

    val endCityProperty = SimpleObjectProperty(city)
    var endCity by endCityProperty
    val endPoint get() = endCity.let { Point(it.x, it.y) }

    // animated properties
    val edgeStartX = SimpleDoubleProperty(startCityProperty.get().x)
    val edgeStartY = SimpleDoubleProperty(startCityProperty.get().y)
    val edgeEndX = SimpleDoubleProperty(startCityProperty.get().x)
    val edgeEndY = SimpleDoubleProperty(startCityProperty.get().y)
    val distance = SimpleDoubleProperty(0.0)

    val distanceNow get() = CitiesAndDistances.distances[CityPair(startCity.id, endCity.id)]?:0.0

    init {
        startCityProperty.onChange {
            sequentialTransition += timeline(play = false) {
                keyframe(speed) {
                    keyvalue(edgeStartX, it?.x ?: 0.0)
                    keyvalue(edgeStartY, it?.y ?: 0.0)
                    keyvalue(distance, distanceNow)
                }
            }
        }
        endCityProperty.onChange {
            sequentialTransition += timeline(play = false) {
                keyframe(speed) {
                    keyvalue(edgeEndX, it?.x ?: 0.0)
                    keyvalue(edgeEndY, it?.y ?: 0.0)
                    keyvalue(distance, distanceNow)
                }
            }
        }
    }

    val nextEdge get() = (Model.edges.firstOrNull { it != this && it.startCity == endCity }) ?:
        (Model.edges.firstOrNull { it != this && it.endCity == endCity }?.also { it.flip() })

    private fun flip() {
        speed = 1.millis
        val city1 = startCity
        val city2 = endCity
        startCity = city2
        endCity = city1
        speed = 200.millis
    }

    val intersectConflicts get() = Model.edges.asSequence()
            .filter { it != this }
            .filter { edge2 ->
                startCity !in edge2.let { setOf(it.startCity, it.endCity) } &&
                        endCity !in edge2.let { setOf(it.startCity, it.endCity) } &&
                    intersect(startPoint, endPoint, edge2.startPoint, edge2.endPoint)
            }


    class Swap(val city1: City,
               val city2: City,
               val property1: ObjectProperty<City>,
               val property2: ObjectProperty<City>
    ) {
        fun execute() {
            property1.set(city2)
            property2.set(city1)
        }
        fun reverse() {
            val p1 = property1.get()
            val p2 = property2.get()
            property1.set(p2)
            property2.set(p1)
        }
    }

    fun attemptSafeSwap(otherEdge: Edge): Swap? {

        val e1 = this
        val e2 = otherEdge

        val startCity1 = startCity
        val endCity1 = endCity
        val startCity2 = otherEdge.startCity
        val endCity2 = otherEdge.endCity

        return sequenceOf(
                Swap(startCity1, startCity2, e1.startCityProperty, e2.startCityProperty),
                Swap(endCity1, endCity2, e1.endCityProperty, e2.endCityProperty),

                Swap(startCity1, endCity2, e1.startCityProperty, e2.endCityProperty),
                Swap(endCity1, startCity2, e1.endCityProperty, e2.startCityProperty)

        ).firstOrNull { swap ->
            swap.execute()
            val result = Model.tourMaintained
            if (!result) {
                swap.reverse()
            }
            result
        }
    }

    override fun toString() = "$startCity-$endCity"
}
object Model {


    val edges = CitiesAndDistances.cities.asSequence()
            .map { Edge(it) }
            .toList()

    val animationDistanceProperty = Bindings.createDoubleBinding(
            Callable<Double> { edges.asSequence().map { it.distance.get() }.sum() },
            *edges.map { it.distance }.toTypedArray()
    )

    val totalDistance get() = Model.edges.map { it.distanceNow }.sum()

    val traverseTour: Sequence<Edge> get() {
        val captured = mutableSetOf<Edge>()

        return generateSequence(edges.first()) {
            it.nextEdge?.takeIf { it !in captured }
        }.onEach { captured += it }
    }

    val tourMaintained get() =
        traverseTour.count() == edges.count()


    val intersectConflicts get() = edges.asSequence()
            .map { edge1 -> edge1.intersectConflicts.map { edge2 -> edge1 to edge2}.sampleOrNull() }
            .filterNotNull()

    fun reset() {
        edges.forEach { it.endCity = it.startCity }
    }
}


enum class SearchStrategy {

    RANDOM {
        override fun execute() {
            val capturedCities = mutableSetOf<Int>()

            val startingEdge = Model.edges.sample()
            var edge = startingEdge

            while(capturedCities.size < CitiesAndDistances.cities.size) {
                capturedCities += edge.startCity.id

                val nextRandom = Model.edges.asSequence()
                        .filter { it.startCity.id !in capturedCities }
                        .sampleOrNull()?:startingEdge

                edge.endCity = nextRandom.startCity
                edge = nextRandom
            }
        }
    },

    GREEDY {
        override fun execute() {
            val capturedCities = mutableSetOf<Int>()

            var edge = Model.edges.first()

            while(capturedCities.size < CitiesAndDistances.cities.size) {
                capturedCities += edge.startCity.id

                val closest = Model.edges.asSequence().filter { it.startCity.id !in capturedCities }
                        .minBy { CitiesAndDistances.distances[CityPair(edge.startCity.id, it.startCity.id)]?:10000.0 }?:Model.edges.first()

                edge.endCity = closest.startCity
                edge = closest
            }
        }
    },

/*    SPECIFIC_SWAP {
        override fun execute() {
            speed = 50.millis

            sequentialTransition += timeline(play=false) {
                delay = 5.seconds
            }

            SearchStrategy.GREEDY.execute()

            speed = 5.seconds

            val edge1 = Model.edges.first { it.startCity.city == "Amsterdam" && it.endCity.city == "London" }
            val edge2 = Model.edges.first { it.startCity.city == "Dublin" && it.endCity.city == "Sofia" }

            edge1.attemptSafeSwap(edge2)
        }

    },*/
    TWO_OPT {
        override fun execute() {

            speed = 200.millis

            SearchStrategy.GREEDY.execute()

            (1..10).forEach {
                Model.intersectConflicts.forEach { (x, y) ->
                    x.attemptSafeSwap(y)
                }
            }

            // TODO - random swaps break everything

/*            (1..100).forEach {
                Model.edges.sampleDistinct(2).toList()
                        .let { it.first() to it.last() }
                        .takeIf { it.first.startCity != it.second.endCity && it.first.endCity != it.second.startCity }
                        ?.also { (e1,e2) ->
                            val oldDistance = Model.totalDistance
                            e1.attemptSafeSwap(e2)?.also {
                                if (oldDistance < Model.totalDistance) {
                                    it.reverse()
                                } else {
                                    Model.intersectConflicts.forEach { (x, y) ->
                                        x.attemptSafeSwap(y)
                                    }
                                }
                            }
                }
            }*/
        }
    };

    abstract fun execute()
}