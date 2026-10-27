package cn.datacraft.job;

/** Not an IllegalStateException: repository exception translation must preserve cancellation. */
class GenerationCancelledException extends RuntimeException {
    GenerationCancelledException(String message){super(message);}
}
