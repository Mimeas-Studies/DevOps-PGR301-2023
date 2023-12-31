package com.example.s3rekognition.controller;

import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.rekognition.model.S3Object;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.example.s3rekognition.TiredClassification;
import com.example.s3rekognition.PPEClassificationResponse;
import com.example.s3rekognition.PPEResponse;
import com.example.s3rekognition.TiredFacesResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;


@RestController
@RequiredArgsConstructor
public class RekognitionController implements ApplicationListener<ApplicationReadyEvent> {

    private final AmazonS3 s3Client;

    private final AmazonRekognition rekognitionClient;

    private final MeterRegistry meterRegistry;

    private static final Logger logger = Logger.getLogger(RekognitionController.class.getName());

    /**
     * This endpoint takes an S3 bucket name in as an argument, scans all the
     * Files in the bucket for Protective Gear Violations.
     * <p>
     *
     * @param bucketName
     * @return
     */
    @GetMapping(value = "/scan-ppe", consumes = "*/*", produces = "application/json")
    @ResponseBody
    public ResponseEntity<PPEResponse> scanForPPE(@RequestParam String bucketName) {
        logger.info("Bucket provided is " + bucketName);
        // List all objects in the S3 bucket
        ListObjectsV2Result imageList = s3Client.listObjectsV2(bucketName);

        // This is all the images in the bucket
        List<S3ObjectSummary> images = imageList.getObjectSummaries();

        List<PPEClassificationResponse> classificationResponses = images.stream()
                .map(image -> new DetectProtectiveEquipmentRequest()
                    .withImage(new Image()
                            .withS3Object(new S3Object()
                                    .withBucket(bucketName)
                                    .withName(image.getKey())))
                    .withSummarizationAttributes(new ProtectiveEquipmentSummarizationAttributes()
                            .withMinConfidence(80f)
                            .withRequiredEquipmentTypes("FACE_COVER"))
                )
                .peek(request -> logger.info("Detecting people in s3://" + bucketName + "/" + request.getImage().getS3Object().getName()))
                .peek(request -> meterRegistry.counter("image scans", "scan type", "ppe").increment())
                .map(request -> {
                    DetectProtectiveEquipmentResult result = rekognitionClient.detectProtectiveEquipment(request);
                    return new PPEClassificationResponse(
                            request.getImage().getS3Object().getName(),
                            result.getPersons().size(),
                            result.getSummary()
                                    .getPersonsWithRequiredEquipment()
                                    .isEmpty()
                    );
                })
                .peek(response -> {
                    meterRegistry.counter("detected people", "scan type", "ppe").increment(response.getPersonCount());
                    if (response.isViolation()) meterRegistry.counter("detected violations", "scan type", "ppe").increment();
                })
                .collect(Collectors.toList());

        // Iterate over each object and scan for PPE
        PPEResponse ppeResponse = new PPEResponse(bucketName, classificationResponses);
        return ResponseEntity.ok(ppeResponse);
    }

    /**
     * This endpoint takes an S3 bucket name in as an argument, scans all the
     * Files in the bucket for tired faces.
     *
     * @param bucketName
     * @return a http response with a json list with scan results
     */
    @GetMapping(value = "/scan-tired", produces = "application/json")
    public ResponseEntity<TiredFacesResponse> scanForTiredFaces(@RequestParam String bucketName) {
        // List all objects in the S3 bucket
        ListObjectsV2Result imageList = s3Client.listObjectsV2(bucketName);

        // This is all the images in the bucket
        List<S3ObjectSummary> images = imageList.getObjectSummaries();

        // Iterate over each object and scan for tiredness by checking if they are confused or scared
        List<TiredClassification> imageResults = images.stream()
                //  Create scan requests
                .map(image -> new DetectFacesRequest()
                        .withImage(new Image()
                                .withS3Object(new S3Object()
                                        .withBucket(bucketName)
                                        .withName(image.getKey()))
                        )
                        .withAttributes(Attribute.ALL)
                )
                .peek(request -> logger.info("Detecting faces in s3://" + bucketName + "/" + request.getImage().getS3Object().getName()))
                .map(request -> {
                    DetectFacesResult result = rekognitionClient.detectFaces(request);
                    meterRegistry.counter("image scans", "scan type", "exhaustion").increment();
                    return TiredClassification.builder()
                            .filename(request.getImage().getS3Object().getName())
                            .violationCount((int) result.getFaceDetails()
                                    .stream()
                                    .peek(face -> meterRegistry.counter("detected people", "scan type", "exhaustion").increment())
                                    .map(FaceDetail::getEmotions)
                                    .filter(emotions -> emotions
                                            .stream()
                                            // Tired is not an emotion, so we match against confused or fear instead.
                                            // This really should use its own model trained to find tired faces.
                                            .filter(emotion ->
                                                    emotion.getType().contentEquals(EmotionName.CONFUSED.name())
                                                            ||
                                                            emotion.getType().contentEquals(EmotionName.FEAR.name())
                                            )
                                            .peek(emotion -> meterRegistry.summary("detection confidence", "scan type", "exhaustion").record(emotion.getConfidence()))
                                            .filter(emotion -> emotion.getConfidence() >= 80f) // Confidence threshold could be a configuration maybe?
                                            .peek(emotion -> logger.info("Detected " + emotion + " in " + request.getImage().getS3Object().getName()))
                                            .count() != 0
                                    )
                                    .peek(detail -> meterRegistry.counter("detected violations", "scan type", "exhaustion").increment())
                                    .count()
                            )
                            .personCount(result.getFaceDetails().size())
                            .build();
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(new TiredFacesResponse(bucketName, imageResults));
    }


    /**
     * Detects if the image has a protective gear violation for the FACE bodypart-
     * It does so by iterating over all persons in a picture, and then again over
     * each body part of the person. If the body part is a FACE and there is no
     * protective gear on it, a violation is recorded for the picture.
     *
     * @param result
     * @return
     */
    private static boolean isViolation(DetectProtectiveEquipmentResult result) {
        return result.getPersons().stream()
                .flatMap(p -> p.getBodyParts().stream())
                .anyMatch(bodyPart -> bodyPart.getName().equals("FACE")
                        && bodyPart.getEquipmentDetections().isEmpty());
    }


    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {

    }
}
