// Types used by Sphynx.
package main

import (
	"sync"
)

type Server struct {
	sync.Mutex
	entities         map[GUID]EntityPtr
	dataDir          string
	unorderedDataDir string
}
type GUID string
type OperationDescription struct {
	Class string
	Data  interface{}
}
type OperationInstance struct {
	GUID      GUID
	Inputs    map[string]GUID
	Outputs   map[string]GUID
	Operation OperationDescription
}

type EntityField struct {
	fieldName string
	data      interface{}
}

type EntityPtr interface {
	name() string
	fields() []EntityField
}

func (e *Scalar) name() string {
	return "Scalar"
}
func (e *VertexSet) name() string {
	return "VertexSet"
}
func (e *EdgeBundle) name() string {
	return "EdgeBundle"
}
func (e *DoubleAttribute) name() string {
	return "DoubleAttribute"
}
func (e *StringAttribute) name() string {
	return "StringAttribute"
}
func (e *DoubleTuple2Attribute) name() string {
	return "DoubleTuple2Attribute"
}

func (e *Scalar) fields() []EntityField {
	return []EntityField{
		EntityField{fieldName: "Value", data: &e.Value},
	}
}
func (e *VertexSet) fields() []EntityField {
	return []EntityField{
		EntityField{fieldName: "Mapping", data: &e.Mapping},
	}
}
func (e *EdgeBundle) fields() []EntityField {
	return []EntityField{
		EntityField{fieldName: "Src", data: &e.Src},
		EntityField{fieldName: "Dst", data: &e.Dst},
		EntityField{fieldName: "EdgeMapping", data: &e.EdgeMapping},
	}
}
func (e *DoubleAttribute) fields() []EntityField {
	return []EntityField{
		EntityField{fieldName: "Values", data: &e.Values},
		EntityField{fieldName: "Defined", data: &e.Defined},
	}
}
func (e *StringAttribute) fields() []EntityField {
	return []EntityField{
		EntityField{fieldName: "Values", data: &e.Values},
		EntityField{fieldName: "Defined", data: &e.Defined},
	}
}
func (e *DoubleTuple2Attribute) fields() []EntityField {
	return []EntityField{
		EntityField{fieldName: "Values1", data: &e.Values1},
		EntityField{fieldName: "Values2", data: &e.Values2},
		EntityField{fieldName: "Defined", data: &e.Defined},
	}
}

type OperationOutput struct {
	outputs map[GUID]EntityPtr
}

func (server *Server) get(guid GUID) (EntityPtr, bool) {
	server.Lock()
	defer server.Unlock()
	entity, exists := server.entities[guid]
	return entity, exists
}

type EdgeBundle struct {
	Src         []int64
	Dst         []int64
	EdgeMapping []int64
}
type VertexSet struct {
	Mapping []int64
}
type Scalar struct {
	Value interface{}
}

type DoubleAttribute struct {
	Values  []float64
	Defined []bool
}
type StringAttribute struct {
	Values  []string
	Defined []bool
}
type DoubleTuple2Attribute struct {
	Values1 []float64
	Values2 []float64
	Defined []bool
}

const (
	EdgeBundleCode            byte = iota
	VertexSetCode             byte = iota
	ScalarCode                byte = iota
	DoubleAttributeCode       byte = iota
	StringAttributeCode       byte = iota
	DoubleTuple2AttributeCode byte = iota
)

type Vertex struct {
	Id int64 `parquet:"name=id, type=INT64"`
}
type Edge struct {
	Id  int64 `parquet:"name=id, type=INT64"`
	Src int64 `parquet:"name=src, type=INT64"`
	Dst int64 `parquet:"name=dst, type=INT64"`
}
type SingleStringAttribute struct {
	Id    int64  `parquet:"name=id, type=INT64"`
	Value string `parquet:"name=value, type=UTF8"`
}
type SingleDoubleAttribute struct {
	Id    int64   `parquet:"name=id, type=INT64"`
	Value float64 `parquet:"name=value, type=DOUBLE"`
}
type SingleDoubleTuple2Attribute struct {
	Id     int64   `parquet:"name=id, type=INT64"`
	Value1 float64 `parquet:"name=value1, type=DOUBLE"`
	Value2 float64 `parquet:"name=value2, type=DOUBLE"`
}
