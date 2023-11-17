package uk.me.cormack.fixture

import java.awt.Color
import kotlin.reflect.KClass
import kotlin.reflect.KType

abstract class Fixture(val key: String, val fixtureName: String, val position: Int) {
    val fixtureProperties: List<FixtureProperty> = this::class.supertypes.fixturePropertyAnnotations

    private val List<KType>.fixturePropertyAnnotations: List<FixtureProperty>
        get() {
            val lightProperties = ArrayList<FixtureProperty>()

            this.forEach { classifier ->
                val classifier = classifier.classifier

                if (classifier is KClass<*>) {
                    lightProperties.addAll(classifier.annotations.mapNotNull { annotation ->
                        if (annotation is FixtureProperty) {
                            annotation
                        } else {
                            null
                        }
                    })
                }
            }

            return lightProperties
        }

    open fun setDimmer(value: UByte = 255u) {
        if (this is FixtureWithDimmer) {
            this.level = 255u
        }
    }

    open fun setSetting(settingName: String, valueName: String) {
        if (this is FixtureWithSettings) {
            (this as FixtureWithSettings).setSetting(settingName, valueName)
        }
    }

    open fun setSlider(sliderName: String, sliderValue: UByte) {
        if (this is FixtureWithSliders) {
            (this as FixtureWithSliders).setSlider(sliderName, sliderValue)
        }
    }

    open fun setColor(color: Color, uv: Boolean = false, fadeMs: Long = 0, level: UByte = 255u) {
        if (this is FixtureWithDimmer) {
            this.level = level
        }

        if (this is FixtureWithColour) {
            this.fadeToColour(color, fadeMs)

            if (this.uvSupport) {
                if (uv) {
                    this.uvLevel = 255u
                } else {
                    this.uvLevel = 0u
                }
            }
        }
    }

    open fun setUv(fallbackColor: Color = Color.BLACK, fadeMs: Long = 0) {
        if (this is FixtureWithDimmer) {
            this.level = 255u
        }

        if (this is FixtureWithColour) {
            if (this.uvSupport) {
                this.setColor(Color.BLACK, true, fadeMs)
            } else {
                this.setColor(fallbackColor, false, fadeMs)
            }
        }
    }

    open  fun blackout() {
        if (this is FixtureWithDimmer) {
            this.level = 0u
        }

        if (this is FixtureWithColour) {
            this.setColor(Color.BLACK)
        }
    }
}
