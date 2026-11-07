package art.gaoge.dance.engine.litert

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Logical tensor shapes from the model FlatBuffer. LiteRT 2.1.5 exposes
 * name-based tensor queries, but its buffer API has no index-based shape query.
 * Native inference still validates and owns the allocated tensor buffers. */
internal data class LiteRtTensorMetadata(val inputs:List<List<Int>>,val outputs:List<List<Int>>) {
    companion object {
        fun read(bytes:ByteArray):LiteRtTensorMetadata {
            require(bytes.size>=8&&bytes.copyOfRange(4,8).contentEquals(byteArrayOf(84,70,76,51))){"MODEL_METADATA_INVALID"}
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun range(at:Long,size:Long):Int {
                require(at>=0&&size>=0&&at+size<=bytes.size.toLong()) {"MODEL_METADATA_BOUNDS"}
                return at.toInt()
            }
            fun i(at:Int):Int=b.getInt(range(at.toLong(),4))
            fun u16(at:Int):Int=b.getShort(range(at.toLong(),2)).toInt() and 65535
            fun ref(at:Int):Int=range(at.toLong()+i(at).toLong(),4)
            fun field(table:Int,slot:Int):Int? {
                val v=range(table.toLong()-i(table).toLong(),4)
                val entry=4+slot*2
                if(entry>=u16(v))return null
                val offset=u16(range(v.toLong()+entry,2))
                return if(offset==0)null else range(table.toLong()+offset,4)
            }
            fun vector(address:Int):Pair<Int,Int> {
                val v=ref(address);val count=i(v);require(count in 0..100_000){"MODEL_METADATA_VECTOR"}
                return range(v.toLong()+4,count.toLong()*4) to count
            }
            fun requiredField(table:Int,slot:Int)=checkNotNull(field(table,slot)){"MODEL_METADATA_FIELD"}
            val model=range(i(0).toLong(),4)
            val (graphs,graphCount)=vector(requiredField(model,2));require(graphCount>0){"MODEL_METADATA_GRAPH"}
            val graph=ref(graphs)
            val (tensors,tensorCount)=vector(requiredField(graph,0))
            fun shapes(slot:Int):List<List<Int>> {
                val (indices,count)=vector(requiredField(graph,slot))
                return (0 until count).map {n->
                    val index=i(indices+n*4);require(index in 0 until tensorCount){"MODEL_METADATA_TENSOR"}
                    val tensor=ref(tensors+index*4)
                    val shape=field(tensor,0)
                    if(shape==null)emptyList() else {
                        val (dimensions,rank)=vector(shape);require(rank<=32){"MODEL_METADATA_RANK"}
                        (0 until rank).map{k->i(dimensions+k*4).also{require(it>=-1){"MODEL_METADATA_DIMENSION"}}}
                    }
                }
            }
            return LiteRtTensorMetadata(shapes(1),shapes(2))
        }
    }
}
