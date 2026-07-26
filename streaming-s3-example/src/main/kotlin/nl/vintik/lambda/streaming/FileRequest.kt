package nl.vintik.lambda.streaming

/**
 * Typed request for a file-serving endpoint: carries the validated file name extracted
 * from the API Gateway `/{proxy+}` path parameter.
 *
 * Produced by [FileKeyResolver] and consumed by [S3Source]. Extend this data class (or
 * create a richer variant) if your use case requires additional fields such as tenant ID,
 * region, or access scope.
 */
data class FileRequest(val fileName: String)
