针对shark-0.8.1进行了二次开发

修改的文件：

  1. src/main/scala/shark/execution/TableScanOperator.scala
  
    通过读取hive表属性shark.table.reader.class来加载对应的TableReader类，获取RDD；
    
    只要将对应的TableReader类所在的jar放在{SHARK_HOME}/lib目录下，重新启动sharkserver即可；
    
    例子可见[shark-tablereader-plugin](https://github.com/4399data/shark-tablereader-plugin)
    

# Shark (Hive on Spark)

Shark is a large-scale data warehouse system for Spark designed to be compatible with
Apache Hive. It can answer Hive QL queries up to 100 times faster than Hive without
modification to the existing data nor queries. Shark supports Hive's query language,
metastore, serialization formats, and user-defined functions.

Shark 0.8.0 requires:
* Scala 2.9.3
* Hive 0.9
* Spark 0.8.x

## For current documentation, see the [Shark Project Wiki](https://github.com/amplab/shark/wiki)
