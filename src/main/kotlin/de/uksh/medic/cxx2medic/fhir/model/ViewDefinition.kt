package de.uksh.medic.cxx2medic.fhir.model

import ca.uhn.fhir.model.api.annotation.Block
import ca.uhn.fhir.model.api.annotation.Child
import ca.uhn.fhir.model.api.annotation.Description
import ca.uhn.fhir.model.api.annotation.ResourceDef
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseBackboneElement
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus

@ResourceDef(name = "ViewDefinition", profile = "http://hl7.org/fhir/uv/sql-on-fhir/StructureDefinition/ViewDefinition")
class ViewDefinition(resourceType: ResourceType): Base()
{
    @Child(name = "url", min = 0, max = 1)
    @Description(shortDefinition = "Canonical identifier for this view definition, represented as a URI (globally unique)")
    var url: UriType? = null

    @Child(name = "identifier", min = 0, max = 1)
    @Description(shortDefinition = "Additional identifier for the view definition")
    var identifier: Identifier? = null

    @Child(name = "name", min = 0, max = 1)
    @Description(shortDefinition = "Name of view definition (computer and database friendly)")
    var name: StringType? = null

    @Child(name = "title", min = 0, max = 1)
    @Description(shortDefinition = "Name for this view definition (human friendly)")
    var title: StringType? = null

    @Child(name = "meta", min = 0, max = 1)
    @Description(shortDefinition = "Metadata about this view")
    var meta: Meta? = null

    @Child(name = "status", min = 1, max = 1)
    @Description(shortDefinition = "draft | active | retired | unknown")
    var status: PublicationStatus = PublicationStatus.UNKNOWN

    @Child(name = "experimental", min = 0, max = 1)
    @Description(shortDefinition = "For testing purposes, not real usage")
    var experimental: BooleanType? = null

    @Child(name = "publisher", min = 0, max = 1)
    @Description(shortDefinition = "Name of the publisher/steward (organization or individual)")
    var publisher: StringType? = null

    @Child(name = "contact", min = 0, max = Child.MAX_UNLIMITED)
    @Description(shortDefinition = "Contact details for the publisher")
    var contact: MutableList<ContactDetail> = mutableListOf()

    @Child(name = "description", min = 0, max = 1)
    @Description(shortDefinition = "Natural language description of the view definition")
    var description: MarkdownType? = null

    @Child(name = "useContext", min = 0, max = Child.MAX_UNLIMITED)
    @Description(shortDefinition = "The context that the content is intended to support")
    var useContext: MutableList<UsageContext> = mutableListOf()

    @Child(name = "copyright", min = 0, max = 1)
    @Description(shortDefinition = "Use and/or publishing restrictions")
    var copyright: MarkdownType? = null

    @Child(name = "FHIR resource for the ViewDefinition", min = 1, max = 1)
    @Description(shortDefinition = "FHIR resource for the ViewDefinition")
    var resource: ResourceType = resourceType

    @Child(name = "fhirVersion", min = 0, max = Child.MAX_UNLIMITED)
    @Description(shortDefinition = "FHIR version(s) of the resource for the ViewDefinition")
    val fhirVersion: MutableList<FHIRVersion> = mutableListOf()

    @Child(name = "constant", min = 0, max = Child.MAX_UNLIMITED)
    @Description(shortDefinition = "Constant that can be used in FHIRPath expressions")
    var constant: MutableList<ConstantComponent> = mutableListOf()

    @Child(name = "select", min = 1, max = Child.MAX_UNLIMITED)
    @Description(shortDefinition = "A collection of columns and nested selects to include in the view.")
    var select: MutableList<SelectComponent> = mutableListOf()

    @Child(name = "where", min = 0, max = Child.MAX_UNLIMITED)
    @Description(shortDefinition = "A series of zero or more FHIRPath constraints to filter resources for the view.")
    var where: MutableList<WhereComponent> = mutableListOf()

    override fun listChildren(result: MutableList<Property>) = TODO("Not yet implemented")

    override fun getIdBase(): String = TODO("Not yet implemented")

    override fun setIdBase(value: String?) = TODO ("Not yet implemented")

    override fun copy(): Base = TODO("Not yet implemented")

    @Block
    class ConstantComponent(name: String, value: Type): BackboneElement(), IBaseBackboneElement
    {
        @Child(name = "name", min = 1, max = 1)
        @Description(shortDefinition = "Name of constant (referred to in FHIRPath as %[name])")
        var name: StringType = StringType(name)

        @Child(
            name = "value", min = 1, max = 1,
            type = [Base64BinaryType::class, BooleanType::class, CanonicalType::class, CodeType::class, DateType::class,
                DateTimeType::class, DecimalType::class, IdType::class, InstantType::class, IntegerType::class,
                OidType::class, StringType::class, PositiveIntType::class, TimeType::class, UnsignedIntType::class,
                UriType::class, UrlType::class, UuidType::class]
        )
        @Description(shortDefinition = "Value of constant")
        var value: Type = value

        override fun copy(): BackboneElement =
            ConstantComponent(this.name.value, this.value.copy())
    }

    @Block
    class SelectComponent: BackboneElement(), IBaseBackboneElement
    {
        @Child(name = "column", min = 0, max = Child.MAX_UNLIMITED)
        @Description(shortDefinition = "A column to be produced in the resulting table.")
        var column: MutableList<SelectColumnComponent> = mutableListOf()

        @Child(name = "select", min = 0, max = Child.MAX_UNLIMITED)
        @Description(shortDefinition = "Nested select relative to parent expression")
        var select: MutableList<SelectComponent> = mutableListOf()

        @Child(name = "forEach", min = 0, max = 1)
        @Description(shortDefinition = "A FHIRPath expression to retrieve the parent element(s) used in the containing select. The default is effectively `\$this`.")
        var forEach: StringType? = null

        @Child(name = "forEachOfNull", min = 0, max = 1)
        @Description(shortDefinition = "Same as forEach, but will produce a row with null values if the collection is empty.")
        var forEachOrNull: StringType? = null

        @Child(name = "unionAll", min = 0, max = Child.MAX_UNLIMITED)
        @Description(shortDefinition = "Creates a union of all rows in the given selection structures.")
        var unionAll: MutableList<SelectComponent> = mutableListOf()

        override fun copy(): BackboneElement = SelectComponent().also {
            it.column = column.map { e -> e.copy() as SelectColumnComponent }.toMutableList()
            it.select = column.map { e -> e.copy() as SelectComponent }.toMutableList()
            it.forEach = forEach?.copy()
            it.forEachOrNull = forEachOrNull?.copy()
            it.unionAll = unionAll.map { e -> e.copy() as SelectComponent }.toMutableList()
        }
    }

    @Block
    class SelectColumnComponent(path: String, name: String): BackboneElement(), IBaseBackboneElement
    {
        @Child(name = "path", min = 1, max = 1)
        @Description(shortDefinition = "FHIRPath expression that creates a column and defines its content")
        var path: StringType = StringType(path)

        @Child(name = "name", min = 1, max = 1)
        @Description(shortDefinition = " \tColumn name produced in the output\n")
        var name: StringType = StringType(name)

        @Child(name = "description", min = 0, max = 1)
        @Description(shortDefinition = "Description of the column")
        var description: MarkdownType? = null

        @Child(name = "collection", min = 0, max = 1)
        @Description(shortDefinition = "Indicates whether the column may have multiple values.")
        var collection: BooleanType? = null

        @Child(name = "type", min = 0, max = 1)
        @Description(shortDefinition = "A FHIR StructureDefinition URI for the column's type.")
        var type: UriType? = null

        @Child(name = "tag", min = 0, max = Child.MAX_UNLIMITED)
        @Description(shortDefinition = "Additional metadata describing the column")
        var tag: MutableList<SelectColumnTagComponent> = mutableListOf()

        override fun copy(): BackboneElement = SelectColumnComponent(this.path.value, this.name.value).also {
            it.description = description?.copy()
            it.collection = collection?.copy()
            it.type = type?.copy()
            it.tag = tag.map { e -> e.copy() as SelectColumnTagComponent }.toMutableList()
        }
    }

    @Block
    class SelectColumnTagComponent(name: String, value: String): BackboneElement(), IBaseBackboneElement
    {
        @Child(name = "name", min = 1, max = 1)
        @Description(shortDefinition = "Name of tag")
        var name: StringType = StringType(name)

        @Child(name = "value", min = 1, max = 1)
        @Description(shortDefinition = "Value of tag")
        var value: StringType = StringType(value)

        override fun copy(): BackboneElement =
            SelectColumnTagComponent(this.name.value, this.value.value)
    }

    @Block
    class WhereComponent(path: String): BackboneElement(), IBaseBackboneElement
    {
        @Child(name = "path", min = 1, max = 1)
        @Description(shortDefinition = "A FHIRPath expression defining a filter condition")
        var path: StringType = StringType(path)

        @Child(name = "description", min = 0, max = 1)
        @Description(shortDefinition = "A human-readable description of the above where constraint.")
        var description: StringType? = null

        override fun copy(): BackboneElement = WhereComponent(this.path.value).also {
            it.description = description?.copy()
        }
    }

    override fun fhirType(): String = "ViewDefinition"

    companion object
    {
        private const val serialVersionUID = 1L
    }
}