package com.unciv.models.ruleset

import com.unciv.models.stats.Stats
import com.unciv.models.translations.getPlaceholderParameters
import com.unciv.models.translations.getPlaceholderText



enum class UniqueType(val text:String, val replacedBy: UniqueType? = null) {

    ConsumesResources("Consumes [amount] [resource]"),
    FreeUnits("[amount] units cost no maintenance"),
    UnitMaintenanceDiscount("[amount]% maintenance costs for [mapUnitFilter] units"),
    @Deprecated("As of 3.16.16")
    DecreasedUnitMaintenanceCostsByFilter("-[amount]% [mapUnitFilter] unit maintenance costs", UnitMaintenanceDiscount),
    @Deprecated("As of 3.16.16")
    DecreasedUnitMaintenanceCostsGlobally("-[amount]% unit upkeep costs", UnitMaintenanceDiscount),
    StatBonusForNumberOfSpecialists("[stats] if this city has at least [amount] specialists")
    ;

    /** For uniques that have "special" parameters that can accept multiple types, we can override them manually
     *  For 95% of cases, auto-matching is fine. */
    private val parameterTypeMap = ArrayList<List<UniqueParameterType>>()

    init {
        for (placeholder in text.getPlaceholderParameters()) {
            val matchingParameterType =
                UniqueParameterType.values().firstOrNull { it.parameterName == placeholder }
                    ?: UniqueParameterType.Unknown
            parameterTypeMap.add(listOf(matchingParameterType))
        }
    }

    val placeholderText = text.getPlaceholderText()

    /** Ordinal determines severity - ordered from most severe at 0 */
    enum class UniqueComplianceErrorSeverity {

        /** This is a problem like "numbers don't parse", "stat isn't stat", "city filter not applicable" */
        RulesetInvariant,

        /** This is a problem like "unit/resource/tech name doesn't exist in ruleset" - definite bug */
        RulesetSpecific,

        /** This is for filters that can also potentially accept free text, like UnitFilter and TileFilter */
        WarningOnly
    }

    /** Maps uncompliant parameters to their required types */
    fun getComplianceErrors(
        unique: Unique,
        ruleset: Ruleset
    ): List<UniqueComplianceError> {
        val errorList = ArrayList<UniqueComplianceError>()
        for ((index, param) in unique.params.withIndex()) {
            val acceptableParamTypes = parameterTypeMap[index]
            val errorTypesForAcceptableParameters =
                acceptableParamTypes.map { it.getErrorSeverity(param, ruleset) }
            if (errorTypesForAcceptableParameters.any { it == null }) continue // This matches one of the types!
            val leastSevereWarning =
                errorTypesForAcceptableParameters.maxByOrNull { it!!.ordinal }!!
            errorList += UniqueComplianceError(param, acceptableParamTypes, leastSevereWarning)
        }
        return errorList
    }
}



class Unique(val text:String) {
    val placeholderText = text.getPlaceholderText()
    val params = text.getPlaceholderParameters()
    val type = UniqueType.values().firstOrNull { it.placeholderText == placeholderText }

    /** This is so the heavy regex-based parsing is only activated once per unique, instead of every time it's called
     *  - for instance, in the city screen, we call every tile unique for every tile, which can lead to ANRs */
    val stats: Stats by lazy {
        val firstStatParam = params.firstOrNull { Stats.isStats(it) }
        if (firstStatParam == null) Stats() // So badly-defined stats don't crash the entire game
        else Stats.parse(firstStatParam)
    }


    fun isOfType(uniqueType: UniqueType) = uniqueType == type

    /** We can't save compliance errors in the unique, since it's ruleset-dependant */
    fun matches(uniqueType: UniqueType, ruleset: Ruleset) = isOfType(uniqueType)
            && uniqueType.getComplianceErrors(this, ruleset).isEmpty()
}


class UniqueMap:HashMap<String, ArrayList<Unique>>() {
    fun addUnique(unique: Unique) {
        if (!containsKey(unique.placeholderText)) this[unique.placeholderText] = ArrayList()
        this[unique.placeholderText]!!.add(unique)
    }

    fun getUniques(placeholderText: String): Sequence<Unique> {
        val result = this[placeholderText]
        if (result == null) return sequenceOf()
        else return result.asSequence()
    }

    fun getUniques(uniqueType: UniqueType) = getUniques(uniqueType.placeholderText)

    fun getAllUniques() = this.asSequence().flatMap { it.value.asSequence() }
}