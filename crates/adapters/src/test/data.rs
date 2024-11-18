use arrow::array::{
    ArrayRef, BooleanArray, Date32Array, Int64Array, Int64Builder, MapBuilder, MapFieldNames,
    StringArray, StringBuilder, StructArray, TimestampMicrosecondArray,
};
use arrow::datatypes::{DataType, Schema, TimeUnit};
use dbsp::utils::Tup2;
use feldera_sqllib::{ByteArray, Date, Time, Timestamp, F32, F64};
use feldera_types::program_schema::{ColumnType, Field, Relation, SqlIdentifier};
use feldera_types::{
    deserialize_table_record, deserialize_without_context, serialize_struct, serialize_table_record,
};
use prop::sample::SizeRange;
use proptest::{collection, prelude::*};
use proptest_derive::Arbitrary;
use rust_decimal::Decimal;
use size_of::SizeOf;
use std::collections::BTreeMap;
use std::string::ToString;
use std::sync::Arc;

#[derive(
    Debug,
    Default,
    PartialEq,
    Eq,
    PartialOrd,
    Ord,
    serde::Serialize,
    serde::Deserialize,
    Clone,
    Hash,
    SizeOf,
    Arbitrary,
    rkyv::Archive,
    rkyv::Serialize,
    rkyv::Deserialize,
)]
#[archive_attr(derive(Ord, Eq, PartialEq, PartialOrd))]
pub struct TestStruct {
    pub id: u32,
    pub b: bool,
    pub i: Option<i64>,
    pub s: String,
}

impl TestStruct {
    pub fn for_id(id: u32) -> Self {
        Self {
            id,
            ..Default::default()
        }
    }
    pub fn schema() -> Vec<Field> {
        vec![
            Field::new("id".into(), ColumnType::bigint(false)),
            Field::new("b".into(), ColumnType::boolean(false)),
            Field::new("i".into(), ColumnType::bigint(true)),
            Field::new("s".into(), ColumnType::varchar(false)),
        ]
    }

    pub fn avro_schema() -> &'static str {
        r#"{
            "type": "record",
            "name": "TestStruct",
            "fields": [
                { "name": "id", "type": "long" },
                { "name": "b", "type": "boolean" },
                { "name": "i", "type": ["null", "long"] },
                { "name": "s", "type": "string" }
            ]
        }"#
    }
}

deserialize_without_context!(TestStruct);

serialize_struct!(TestStruct()[4]{
    id["id"]: u32,
    b["b"]: bool,
    i["i"]: Option<i64>,
    s["s"]: String
});

/// Generate a batch of records no larger that `size`.
///
/// Makes sure all elements in the vector are unique and ordered.
/// This guarantees that the number and order of values remains the same
/// when they are assembled in a Z-set, which simplifies testing.
pub fn generate_test_batch(size: usize) -> impl Strategy<Value = Vec<TestStruct>> {
    collection::vec(any::<TestStruct>(), 0..=size).prop_map(|v| {
        v.into_iter()
            .enumerate()
            .map(|(i, mut val)| {
                val.id = i as u32;
                val
            })
            .collect::<Vec<_>>()
    })
}

/// Generate up to `max_batches` batches, up to `max_records` each.
///
/// Makes sure elements are unique and ordered across all batches.
pub fn generate_test_batches(
    min_batches: usize,
    max_batches: usize,
    max_records: usize,
) -> impl Strategy<Value = Vec<Vec<TestStruct>>> {
    collection::vec(
        collection::vec(any::<TestStruct>(), 0..=max_records),
        min_batches..=max_batches,
    )
    .prop_map(|batches| {
        let mut index = 0;
        batches
            .into_iter()
            .map(|batch| {
                batch
                    .into_iter()
                    .map(|mut val| {
                        val.id = index;
                        index += 1;
                        val
                    })
                    .collect::<Vec<_>>()
            })
            .collect::<Vec<_>>()
    })
}

pub fn generate_test_batches_with_weights(
    max_batches: usize,
    max_records: usize,
) -> impl Strategy<Value = Vec<Vec<Tup2<TestStruct, i64>>>> {
    collection::vec(
        collection::vec(
            (any::<TestStruct>(), -2i64..=2i64).prop_map(|(x, y)| Tup2(x, y)),
            0..=max_records,
        ),
        0..=max_batches,
    )
    .prop_map(|batches| {
        let mut index = 0;
        batches
            .into_iter()
            .map(|batch| {
                batch
                    .into_iter()
                    .map(|mut val| {
                        val.0.id = index;
                        index += 1;
                        val
                    })
                    .collect::<Vec<_>>()
            })
            .collect::<Vec<_>>()
    })
}

/// This struct mimics the field naming schema of the compiler.
#[derive(
    Debug,
    Default,
    PartialEq,
    Eq,
    PartialOrd,
    Ord,
    serde::Serialize,
    serde::Deserialize,
    Clone,
    Hash,
    SizeOf,
    rkyv::Archive,
    rkyv::Serialize,
    rkyv::Deserialize,
    Arbitrary,
)]
#[archive_attr(derive(Clone, Ord, Eq, PartialEq, PartialOrd))]
#[archive(compare(PartialEq, PartialOrd))]
pub struct EmbeddedStruct {
    #[serde(rename = "a")]
    pub field: bool,
}

serialize_table_record!(EmbeddedStruct[1]{
    r#field["a"]: bool
});

deserialize_table_record!(EmbeddedStruct["EmbeddedStruct", 1] {
    (r#field, "a", false, bool, None)
});

/// This struct mimics the field naming schema of the compiler.
#[derive(
    Debug,
    Default,
    PartialEq,
    Eq,
    PartialOrd,
    Ord,
    serde::Serialize,
    serde::Deserialize,
    Clone,
    Hash,
    SizeOf,
    rkyv::Archive,
    rkyv::Serialize,
    rkyv::Deserialize,
)]
#[archive_attr(derive(Ord, Eq, PartialEq, PartialOrd))]
pub struct TestStruct2 {
    #[serde(rename = "id")]
    pub field: i64,
    #[serde(rename = "name")]
    pub field_0: Option<String>,
    #[serde(rename = "b")]
    pub field_1: bool,
    #[serde(rename = "ts")]
    pub field_2: Timestamp,
    #[serde(rename = "dt")]
    pub field_3: Date,
    // DeltaLake doesn't understand time
    // #[serde(rename = "t")]
    // pub field_4: Time,
    #[serde(rename = "es")]
    pub field_5: Option<EmbeddedStruct>,
    #[serde(rename = "m")]
    pub field_6: Option<BTreeMap<String, i64>>,
}

impl Arbitrary for TestStruct2 {
    type Parameters = ();

    type Strategy = BoxedStrategy<TestStruct2>;

    fn arbitrary_with(_params: Self::Parameters) -> Self::Strategy {
        (
            i64::arbitrary(),
            String::arbitrary(),
            bool::arbitrary(),
            // Generate timestamps within a 1-year range
            1704070800u32..1735693200,
            0u32..100_000,
            // (0u64..24 * 60 * 60 * 1_000_000),
            EmbeddedStruct::arbitrary_with(()),
            // Generate small maps, otherwise proptests take forever.
            BTreeMap::<String, i64>::arbitrary_with((
                SizeRange::from(0..2),
                Default::default(),
                Default::default(),
            )),
        )
            .prop_map(|(f, f0, f1, f2, f3, /*f4,*/ f5, f6)| TestStruct2 {
                field: f,
                field_0: if f1 { Some(f0) } else { None },
                field_1: f1,
                field_2: Timestamp::new(f2 as i64 * 1_000),
                field_3: Date::new(f3 as i32),
                // field_4: Time::new(f4 * 1000),
                field_5: Some(f5),
                field_6: Some(f6),
            })
            .boxed()
    }
}

impl TestStruct2 {
    pub fn data() -> Vec<TestStruct2> {
        vec![
            TestStruct2 {
                field: 1,
                field_0: Some("test".to_string()),
                field_1: false,
                field_2: Timestamp::new(1000),
                field_3: Date::new(1),
                // field_4: Time::new(1),
                field_5: Some(EmbeddedStruct { field: false }),
                field_6: Some(BTreeMap::from([
                    ("foo".to_string(), 100),
                    ("bar".to_string(), 200),
                ])),
            },
            TestStruct2 {
                field: 2,
                field_0: None,
                field_1: true,
                field_2: Timestamp::new(2000),
                field_3: Date::new(12),
                // field_4: Time::new(1_000_000_000),
                field_5: Some(EmbeddedStruct { field: true }),
                field_6: Some(BTreeMap::new()),
            },
        ]
    }

    pub fn arrow_schema() -> Arc<Schema> {
        Arc::new(Schema::new(vec![
            arrow::datatypes::Field::new("id", DataType::Int64, false),
            arrow::datatypes::Field::new("name", DataType::Utf8, true),
            arrow::datatypes::Field::new("b", DataType::Boolean, false),
            arrow::datatypes::Field::new(
                "ts",
                DataType::Timestamp(TimeUnit::Microsecond, None),
                false,
            ),
            arrow::datatypes::Field::new("dt", DataType::Date32, false),
            // arrow::datatypes::Field::new("t", DataType::Time64(TimeUnit::Nanosecond), false),
            arrow::datatypes::Field::new(
                "es",
                DataType::Struct(arrow::datatypes::Fields::from(vec![
                    arrow::datatypes::Field::new("a", DataType::Boolean, false),
                ])),
                true,
            ),
            arrow::datatypes::Field::new_map(
                "m",
                "entries",
                arrow::datatypes::Field::new("key", DataType::Utf8, false),
                arrow::datatypes::Field::new("value", DataType::Int64, false),
                false,
                true,
            ),
        ]))
    }

    pub fn avro_schema() -> &'static str {
        r#"{
            "type": "record",
            "name": "TestStruct2",
            "connect.name": "test_namespace.TestStruct2",
            "fields": [
                { "name": "id", "type": "long" },
                { "name": "name", "type": ["string", "null"] },
                { "name": "b", "type": "boolean" },
                { "name": "ts", "type": "long", "logicalType": "timestamp-micros" },
                { "name": "dt", "type": "int", "logicalType": "date" },
                {
                    "name": "es",
                    "type":
                        [{
                            "type": "record",
                            "name": "EmbeddedStruct",
                            "fields": [
                                { "name": "a", "type": "boolean" }
                            ]
                        }, "null"]
                },
                {
                    "name": "m",
                    "type":
                        [{
                            "type": "map",
                            "values": "long"
                        }, "null"]
                }
            ]
        }"#
    }

    pub fn schema() -> Vec<Field> {
        vec![
            Field::new("id".into(), ColumnType::bigint(false)),
            Field::new("name".into(), ColumnType::varchar(true)),
            Field::new("b".into(), ColumnType::boolean(false)),
            Field::new("ts".into(), ColumnType::timestamp(false)),
            Field::new("dt".into(), ColumnType::date(false)),
            Field::new(
                "es".into(),
                ColumnType::structure(true, &[Field::new("a".into(), ColumnType::boolean(false))]),
            ),
            Field::new(
                "m".into(),
                ColumnType::map(true, ColumnType::varchar(false), ColumnType::bigint(false)),
            ),
        ]
    }

    pub fn schema_with_lateness() -> Vec<Field> {
        let fields = vec![
            Field::new("id".into(), ColumnType::bigint(false)).with_lateness("1000"),
            Field::new("name".into(), ColumnType::varchar(true)),
            Field::new("b".into(), ColumnType::boolean(false)),
            Field::new("ts".into(), ColumnType::timestamp(false))
                .with_lateness("interval '10 days'"),
            Field::new("dt".into(), ColumnType::date(false)),
            Field::new(
                "es".into(),
                ColumnType::structure(true, &[Field::new("a".into(), ColumnType::boolean(false))]),
            ),
            Field::new(
                "m".into(),
                ColumnType::map(true, ColumnType::varchar(false), ColumnType::bigint(false)),
            ),
        ];

        fields
    }

    pub fn relation_schema() -> Relation {
        Relation {
            name: SqlIdentifier::new("TestStruct2", false),
            fields: Self::schema(),
            materialized: false,
            properties: BTreeMap::new(),
        }
    }

    pub fn make_arrow_array(data: &[TestStruct2]) -> Vec<ArrayRef> {
        let row0: Vec<i64> = data.iter().map(|r| r.field).collect();
        let row1: Vec<Option<String>> = data.iter().map(|r| r.field_0.clone()).collect();
        let row2: Vec<bool> = data.iter().map(|r| r.field_1).collect();
        let row3: Vec<i64> = data
            .iter()
            .map(|r| r.field_2.milliseconds() * 1000)
            .collect();
        let row4: Vec<i32> = data.iter().map(|r| r.field_3.days()).collect();
        /*let row5: Vec<i64> = data
        .iter()
        .map(|r| r.field_4.nanoseconds() as i64)
        .collect();*/
        let row6_field = Arc::new(arrow::datatypes::Field::new("a", DataType::Boolean, false));
        let row6: Vec<Option<bool>> = data
            .iter()
            .map(|r| r.field_5.as_ref().map(|emb_struct| emb_struct.field))
            .collect();
        let row6_booleans = Arc::new(BooleanArray::from(row6));

        let string_builder = StringBuilder::new();
        let int_builder = Int64Builder::new();

        let mut map_builder = MapBuilder::new(
            Some(MapFieldNames {
                entry: "entries".to_string(),
                key: "key".to_string(),
                value: "value".to_string(),
            }),
            string_builder,
            int_builder,
        )
        .with_values_field(arrow::datatypes::Field::new(
            "value",
            DataType::Int64,
            false,
        ));
        for x in data.iter() {
            if let Some(mp) = &x.field_6 {
                for (key, val) in mp.iter() {
                    map_builder.keys().append_value(key);
                    map_builder.values().append_value(*val);
                }
                map_builder.append(true).unwrap()
            } else {
                map_builder.append(false).unwrap()
            }
        }
        let map_array = map_builder.finish();

        vec![
            Arc::new(Int64Array::from(row0)),
            Arc::new(StringArray::from(row1)),
            Arc::new(BooleanArray::from(row2)),
            Arc::new(TimestampMicrosecondArray::from(row3)),
            Arc::new(Date32Array::from(row4)),
            // Arc::new(Time64NanosecondArray::from(row5)),
            Arc::new(StructArray::from(vec![(
                row6_field,
                row6_booleans as ArrayRef,
            )])),
            Arc::new(map_array),
        ]
    }
}

serialize_table_record!(TestStruct2[7]{
    r#field["id"]: i64,
    r#field_0["name"]: Option<String>,
    r#field_1["b"]: bool,
    r#field_2["ts"]: Timestamp,
    r#field_3["dt"]: Date,
    // r#field_4["t"]: Time,
    r#field_5["es"]: Option<EmbeddedStruct>,
    r#field_6["m"]: Option<Map<String, i64>>
});

deserialize_table_record!(TestStruct2["TestStruct", 7   ] {
    (r#field, "id", false, i64, None),
    (r#field_0, "name", false, Option<String>, Some(None)),
    (r#field_1, "b", false, bool, None),
    (r#field_2, "ts", false, Timestamp, None),
    (r#field_3, "dt", false, Date, None),
    // (r#field_4, "t", false, Time, None),
    (r#field_5, "es", false, Option<EmbeddedStruct>, Some(None)),
    (r#field_6, "m", false, Option<BTreeMap<String, i64>>, Some(None))
});

/// Record in the databricks people dataset.
#[derive(
    Debug,
    Default,
    PartialEq,
    Eq,
    PartialOrd,
    Ord,
    serde::Serialize,
    serde::Deserialize,
    Clone,
    Hash,
    SizeOf,
    rkyv::Archive,
    rkyv::Serialize,
    rkyv::Deserialize,
)]
#[archive_attr(derive(Ord, Eq, PartialEq, PartialOrd))]
pub struct DatabricksPeople {
    pub id: i32,
    pub first_name: Option<String>,
    pub middle_name: Option<String>,
    pub last_name: Option<String>,
    pub gender: Option<String>,
    pub birth_date: Option<Timestamp>,
    pub ssn: Option<String>,
    pub salary: Option<i32>,
}

impl DatabricksPeople {
    pub fn schema() -> Vec<Field> {
        vec![
            Field::new("id".into(), ColumnType::int(false)),
            Field::new("firstName".into(), ColumnType::varchar(true)),
            Field::new("middleName".into(), ColumnType::varchar(true)),
            Field::new("lastName".into(), ColumnType::varchar(true)),
            Field::new("gender".into(), ColumnType::varchar(true)),
            Field::new("birthDate".into(), ColumnType::timestamp(true)),
            Field::new("ssn".into(), ColumnType::varchar(true)),
            Field::new("salary".into(), ColumnType::int(true)),
        ]
    }
}

serialize_table_record!(DatabricksPeople[8]{
    r#id["id"]: i32,
    r#first_name["firstname"]: Option<String>,
    r#middle_name["middlename"]: Option<String>,
    r#last_name["lastname"]: Option<String>,
    r#gender["gender"]: Option<String>,
    r#birth_date["birthdate"]: Option<Timestamp>,
    r#ssn["ssn"]: Option<String>,
    r#salary["salary"]: Option<i32>
});

deserialize_table_record!(DatabricksPeople["DatabricksPeople", 8] {
    (r#id, "id", false, i32, None),
    (r#first_name, "firstname", false, Option<String>, Some(None)),
    (r#middle_name, "middlename", false, Option<String>, Some(None)),
    (r#last_name, "lastname", false, Option<String>, Some(None)),
    (r#gender, "gender", false, Option<String>, Some(None)),
    (r#birth_date, "birthdate", false, Option<Timestamp>, Some(None)),
    (r#ssn, "ssn", false, Option<String>, Some(None)),
    (r#salary, "salary", false, Option<i32>, Some(None))
});

/// Struct will all types supported by the Iceberg connector.
#[derive(
    Debug,
    Default,
    PartialEq,
    Eq,
    PartialOrd,
    Ord,
    serde::Serialize,
    serde::Deserialize,
    Clone,
    Hash,
    SizeOf,
    rkyv::Archive,
    rkyv::Serialize,
    rkyv::Deserialize,
)]
#[archive_attr(derive(Ord, Eq, PartialEq, PartialOrd))]
pub struct IcebergTestStruct {
    pub b: bool,
    pub i: i32,
    pub l: i64,
    pub r: F32,
    pub d: F64,
    pub dec: Decimal,
    pub dt: Date,
    pub tm: Time,
    pub ts: Timestamp,
    pub s: String,
    //pub uuid: ByteArray,
    pub fixed: ByteArray,
    pub varbin: ByteArray,
}

impl Arbitrary for IcebergTestStruct {
    type Parameters = ();

    type Strategy = BoxedStrategy<IcebergTestStruct>;

    fn arbitrary_with(_params: Self::Parameters) -> Self::Strategy {
        (
            // Split into two tuples with <12 fields each.
            (
                bool::arbitrary(),
                i32::arbitrary(),
                i64::arbitrary(),
                f32::arbitrary(),
                f64::arbitrary(),
                0..1_000_000i128,
                // Scale
                0..3u32,
                0i32..100_000,
                // Time in nanos
                0u64..24 * 3600 * 1_000_000_000,
                // Generate timestamps within a 1-year range
                1704070800i64..1735693200,
            ),
            // String in the range "0".."1000"
            (
                0i32..1000,
                // // UUID
                // collection::vec(u8::arbitrary(), 16..=16),
                // Fixed
                collection::vec(u8::arbitrary(), 5..=5),
                // Varbinary
                collection::vec(u8::arbitrary(), 0..=10),
            ),
        )
            .prop_map(
                |(
                    (b, i, l, r, d, dec_num, dec_scale, dt, tm, ts),
                    (s, /*uuid,*/ fixed, varbin),
                ): (
                    (bool, i32, i64, f32, f64, i128, u32, i32, u64, i64),
                    (i32, /*Vec<u8>,*/ Vec<u8>, Vec<u8>),
                )| {
                    IcebergTestStruct {
                        b,
                        i,
                        l,
                        r: F32::new(r),
                        d: F64::new(d),
                        dec: Decimal::from_i128_with_scale(dec_num, dec_scale),
                        dt: Date::new(dt),
                        tm: Time::new(tm),
                        ts: Timestamp::new(ts),
                        s: s.to_string(),
                        // uuid: ByteArray::from_vec(uuid),
                        fixed: ByteArray::from_vec(fixed),
                        varbin: ByteArray::new(&varbin),
                    }
                },
            )
            .boxed()
    }
}

impl IcebergTestStruct {
    pub fn arrow_schema() -> Arc<Schema> {
        Arc::new(Schema::new(vec![
            arrow::datatypes::Field::new("b", DataType::Boolean, false),
            arrow::datatypes::Field::new("i", DataType::Int32, false),
            arrow::datatypes::Field::new("l", DataType::Int64, false),
            arrow::datatypes::Field::new("r", DataType::Float32, false),
            arrow::datatypes::Field::new("d", DataType::Float64, false),
            arrow::datatypes::Field::new("dec", DataType::Decimal128(10, 3), false),
            arrow::datatypes::Field::new("dt", DataType::Date32, false),
            arrow::datatypes::Field::new("tm", DataType::Time64(TimeUnit::Microsecond), false),
            arrow::datatypes::Field::new(
                "ts",
                DataType::Timestamp(TimeUnit::Microsecond, None),
                false,
            ),
            arrow::datatypes::Field::new("s", DataType::Utf8, false),
            // arrow::datatypes::Field::new("uuid", DataType::FixedSizeBinary(16), false),
            arrow::datatypes::Field::new("fixed", DataType::FixedSizeBinary(5), false),
            arrow::datatypes::Field::new("varbin", DataType::Binary, false),
        ]))
    }

    pub fn schema() -> Vec<Field> {
        vec![
            Field::new("b".into(), ColumnType::boolean(false)),
            Field::new("i".into(), ColumnType::int(false)),
            Field::new("l".into(), ColumnType::bigint(false)),
            Field::new("r".into(), ColumnType::real(false)),
            Field::new("d".into(), ColumnType::double(false)),
            Field::new("dec".into(), ColumnType::decimal(10, 3, false)),
            Field::new("dt".into(), ColumnType::date(false)),
            Field::new("tm".into(), ColumnType::time(false)),
            Field::new("ts".into(), ColumnType::timestamp(false)),
            Field::new("s".into(), ColumnType::varchar(false)),
            // Field::new("uuid".into(), ColumnType::fixed(16, false)),
            Field::new("fixed".into(), ColumnType::fixed(5, false)),
            Field::new("varbin".into(), ColumnType::varbinary(false)),
        ]
    }

    pub fn schema_with_lateness() -> Vec<Field> {
        let fields = vec![
            Field::new("b".into(), ColumnType::boolean(false)),
            Field::new("i".into(), ColumnType::int(false)),
            Field::new("l".into(), ColumnType::bigint(false)),
            Field::new("r".into(), ColumnType::real(false)),
            Field::new("d".into(), ColumnType::double(false)),
            Field::new("dec".into(), ColumnType::decimal(10, 3, false)),
            Field::new("dt".into(), ColumnType::date(false)),
            Field::new("tm".into(), ColumnType::time(false)),
            Field::new("ts".into(), ColumnType::timestamp(false))
                .with_lateness("interval '10 days'"),
            Field::new("s".into(), ColumnType::varchar(false)),
            // Field::new("uuid".into(), ColumnType::fixed(16, false)),
            Field::new("fixed".into(), ColumnType::fixed(5, false)),
            Field::new("varbin".into(), ColumnType::varbinary(false)),
        ];

        fields
    }

    pub fn relation_schema() -> Relation {
        Relation {
            name: SqlIdentifier::new("IcebergTestStruct", false),
            fields: Self::schema(),
            materialized: false,
            properties: BTreeMap::new(),
        }
    }
}

serialize_table_record!(IcebergTestStruct[13]{
    b["b"]: bool,
    i["i"]: i32,
    l["l"]: i64,
    r["r"]: F32,
    d["d"]: F64,
    dec["dec"]: Decimal,
    dt["dt"]: Date,
    tm["tm"]: Time,
    ts["ts"]: Timestamp,
    s["s"]: String,
    // uuid["uuid"]: ByteArray,
    fixed["fixed"]: ByteArray,
    varbin["varbin"]: ByteArray
});

deserialize_table_record!(IcebergTestStruct["IcebergTestStruct", 13] {
    (b, "b", false, bool, None),
    (i, "i", false, i32, None),
    (l, "l", false, i64, None),
    (r, "r", false, F32, None),
    (d, "d", false, F64, None),
    (dec, "dec", false, Decimal, None),
    (dt, "dt", false, Date, None),
    (tm, "tm", false, Time, None),
    (ts, "ts", false, Timestamp, None),
    (s, "s", false, String, None),
    // (uuid, "uuid", false, ByteArray, None),
    (fixed, "fixed", false, ByteArray, None),
    (varbin, "varbin", false, ByteArray, None)
});
